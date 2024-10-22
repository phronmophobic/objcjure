(ns com.phronemophobic.objcjure
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.clj-libffi.callback :as cb]
            [com.phronemophobic.clong.gen.dtype-next :as gen]
            [tech.v3.datatype.struct :as dt-struct]
            [tech.v3.datatype.protocols :as dtype-proto]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype :as dtype]
            [tech.v3.datatype.ffi :as dt-ffi]
            )
  (:import
   java.io.PushbackReader
   [tech.v3.datatype.ffi Pointer])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce not-garbage (atom #{}))
(defn ref! [o]
  (swap! not-garbage conj o)
  o)

;; /usr/include/objc/objc-runtime.h

(def ignored-structs
  #{:clong/__darwin_arm_neon_state64
    :clong/__darwin_arm_neon_state})

(def ignored-fns
  #{"protocol_getMethodDescription"
    "class_createInstanceFromZone"
    "object_copyFromZone"
    "objc_msgSend" })

(defn keep-function? [f]
  (let [nm (:symbol f)]
    (and (not (str/ends-with? nm "_stret"))
         (not (ignored-fns nm))
         (or (str/starts-with? nm "class_")
             (str/starts-with? nm "objc_")
             (str/starts-with? nm "protocol_")
             (str/starts-with? nm "sel_")
             (str/starts-with? nm "object_")
             (str/starts-with? nm "method_")))))

(def wanted-structs
  #{"struct Block_descriptor_1"
    "struct Block_literal_1"
    "struct objc_method_description"})

(defn tweak-api [api]
  (-> api
      (update :structs
              (fn [structs]
                (filter #(wanted-structs (:spelling %))
                        structs)))
      (update :functions
              (fn [fns]
                (filter keep-function? fns)))))

(defn ^:private write-edn [w obj]
  (binding [*print-length* nil
            *print-level* nil
            *print-dup* false
            *print-meta* false
            *print-readably* true

            ;; namespaced maps not part of edn spec
            *print-namespace-maps* false

            *out* w]
    (pr obj)))

(defn dump-api []
  (let [outf (io/file
              "resources"
              "com"
              "phronemophobic"
              "objcjure"
              "api.edn")]
    (.mkdirs (.getParentFile outf))
    (with-open [w (io/writer outf)]
      (write-edn w
                 ((requiring-resolve 'com.phronemophobic.clong.clang/easy-api)
                  (.getCanonicalPath (io/file "headers"
                                              "objc.h")))))))

(defn load-api []
  (with-open [rdr (io/reader
                   (io/resource
                    "com/phronemophobic/objcjure/api.edn"))
              rdr (java.io.PushbackReader. rdr)]
    (edn/read rdr)))

(def full-api
  (load-api)
  #_(clong/easy-api "/Users/adrian/workspace/objcjure/headers/objc.h"))
(def api (tweak-api full-api))

(def dtype-api (gen/api->library-interface api))
(def dtype-structs (gen/api->structs api))
(doseq [[id fields] dtype-structs]
  (dt-struct/define-datatype! id fields))

(dt-ffi/define-library-interface dtype-api)

(defn arg->dtype [arg]
  (cond
    (nil? arg) :pointer?
    (instance? Long arg) :int64
    (instance? Integer arg) :int32
    (instance? Short arg) :int16
    (instance? Byte arg) :int8
    (instance? Double arg) :float64
    (instance? Float arg) :float32
    (char? arg) :int8
    (instance? tech.v3.datatype.struct.Struct arg) (dtype-proto/datatype arg)

    (dt-ffi/convertible-to-pointer? arg) :pointer

    :else (throw (ex-info "Unsupported arg dtype"
                          {:arg arg}))))


(defn -call-args [id ret & args]
  (let [call-args (into [:pointer id]
                        (mapcat (fn [arg]
                                  [(arg->dtype arg)
                                   arg]))
                        args)]
    call-args))

(defn objc-msgSend [id ret & args]
  (let [call-args (into [:pointer id]
                        (mapcat (fn [arg]
                                  [(arg->dtype arg)
                                   arg]))
                        args)]
    (apply
     ffi/call "objc_msgSend"
     ret
     call-args)))


(def USE_VARARGS_SHIFT 7)
;; doesn't work
#_(defn objc-msgSend-varargs [id num-args ret & args]
  ;; int callFlags = this.callFlags | ((fixedArgs & USE_VARARGS) << USE_VARARGS_SHIFT);
  (let [call-flags (bit-shift-left
                    (bit-and Function/USE_VARARGS
                             num-args)
                    USE_VARARGS_SHIFT)
        f (.getFunction ^NativeLibrary @process-lib "objc_msgSend" call-flags)]
    (.invoke f ret (to-array (into [id] args)))))

(defmacro defenum [sym]
  `(def ~sym
     (->> (:enums api)
          (filter #(= ~(name sym) (:spelling %)))
          first
          :value)))
(defenum BLOCK_IS_GLOBAL)
(defenum BLOCK_HAS_STRET)

;; - block support
;;    - https://clang.llvm.org/docs/Block-ABI-Apple.html
;;    - https://www.galloway.me.uk/2012/10/a-look-inside-blocks-episode-1/
(def block-descriptor-size (-> (dt-struct/get-struct-def :Block_descriptor_1)
                               :datatype-size))
(defn make-block [f ret-type arg-types]
  (let [^Pointer
        ;; block callbacks always have implicit first argument
        fptr (ref! (cb/make-callback (fn [_ & args]
                                       (apply f args))
                                     ret-type
                                     (cons :pointer arg-types)))
        
        block-descriptor (ref!
                          (dt-struct/map->struct :Block_descriptor_1
                                                 {:size block-descriptor-size}
                                                 :gc))
        
        isa (ffi/dlsym ffi/RTLD_DEFAULT (dt-ffi/string->c "_NSConcreteGlobalBlock") )
        _ (assert isa "isa could not be found.")
        block (ref!
               (dt-struct/map->struct :Block_literal_1
                                      {:isa (.address ^Pointer isa)
                                       :flags (bit-or
                                               BLOCK_IS_GLOBAL
                                               BLOCK_HAS_STRET)
                                       :invoke (.address fptr)
                                       :descriptor (.address (dt-ffi/->pointer block-descriptor))}
                                      :gc))]
    (dt-ffi/->pointer block)))


(defn invoke-block
  "Untested. Should work... theoretically."
  [block ret-type & types-and-args]
  (let [size (:datatype-size (dt-struct/get-struct-def :Block_literal_1))
        block-struct (dt-struct/inplace-new-struct
                      :Block_literal_1
                      (native-buffer/wrap-address (.address (dt-ffi/->pointer block))
                                                  size
                                                  nil))]
    (apply
     ffi/call-ptr
     (ffi/long->pointer (:invoke block-struct))
     ret-type
     ;; implicit first arg
     :pointer block
     types-and-args)))

(comment
  ;; Syntax ideas 7/13

  ;; block
  ;; (fn ^void [^Pointer a ^int b] )

  ;; set! -> property setters

  ;; vectors -> function calls
  ;; @[] -> NSArray
  ;; @strings -> NSString
  ;; @sets -> NSSet
  ;; @{} -> NSDictionary
  ;; () -> clojure calls?

  ;; let -> let
  ;; use ~ embed clojure code


  ,)

(def ^{:private true} prim->dtype
  {'int :int32
   'long :int64
   'float :float32
   'double :float64
   'void :void
   'short :int16
   'boolean :int8
   'byte :int8
   'char :int8})

(declare objc-syntax)

(defn objc-syntax-vector [env form]
  (when (< (count form)
           2)
    (throw (ex-info "Vectors must have at least two elements."
                    {:form form})))
  (let [tag (-> form meta :tag)
        dtype (if tag
                (if-let [tag-type (get prim->dtype tag)]
                  tag-type
                  (keyword tag))
                :pointer)
        ret## (gensym)]
    `(let [~ret## (objc-msgSend
                 ~(objc-syntax env (first form))
                 ~dtype
                 ~@(if (= 2 (count form))
                     [`(sel_registerName (dt-ffi/string->c ~(str (name (second form)))))]
                     (eduction
                      (map (fn [form]
                             (if (keyword? form)
                               `(sel_registerName (dt-ffi/string->c ~(str (name form) ":")))
                               (objc-syntax env form)))
                           (rest form)))))]
       ~(if (= 'boolean tag)
          `(not (zero? ~ret##))
          ret##))))

(def ^:dynamic *sci-ctx* nil)
(def sci-resolve (try
                   @(requiring-resolve 'sci.core/resolve)
                   (catch Exception e
                     nil)))
(defn objc-syntax-symbol [env form]
  (cond

    (contains? env form) form

    ;; try to resolve symbol
    ;; if it can be resolved, leave it
    (if *sci-ctx*
      (if sci-resolve
        (sci-resolve *sci-ctx* form)
        (throw (ex-info "*sci-ctx* set, but sci-resolve not found."
                        {})))
      (resolve env form))
    form

    ;; otherwise, assume it's a class name
    :else
    `(if-let [cls# (objc_getClass (dt-ffi/string->c ~(name form)))]
       cls#
       (throw (ex-info "Unable to resolve symbol"
                       {:sym (quote ~form)})))))

(def ^:private supported-block-types
  '{byte :int8
    short :int16
    int :int32
    long :int64
    float :float32
    double :float64
    pointer :pointer
    pointer? :pointer?
    void :void})
(defn ^:private extract-type
  "Finds the first matching type hint in meta of `o`. Assumes :pointer if no matching hint found."
  [o]
  (if-let [tag (:tag (meta o))]
    (if-let [dtype (get supported-block-types tag)]
      dtype
      (if (dt-struct/struct-datatype? (keyword tag))
        (keyword tag)
        (throw (ex-info "Unsupported callback type"
                        {:o o
                         :tag tag}))))
    ;; default to pointer
    :pointer))
(defn objc-syntax-fn [env form]
  (let [[_fn bindings & body] form]
    `(make-block
      ;; must remove meta tags since some objc supported tags
      ;; can't compile
      (fn ~(into []
                 (map #(with-meta % {}))
                 bindings)
        ~@body)
      ~(extract-type bindings)
      ~(mapv extract-type bindings))))

(defn objc-syntax-seq [env form]
  (if-let [verb (first form)]
    (case verb

      clojure.core/deref
      (let [subject (second form)]
        (cond
          (vector? subject) (if (seq subject)
                              `(let [v# ~(into []
                                               (map #(objc-syntax env %))
                                               (second form))
                                     len# (count v#)
                                     mem# (dtype/make-container
                                           :native-heap
                                           :int64
                                           (into []
                                                 (map #(.address (dt-ffi/->pointer %)))
                                                 v#))]
                                 (objc
                                  [NSArray :arrayWithObjects:count mem# len#]))
                              `(objc [NSArray array]))
          (string? subject) `(objc-msgSend 
                              (objc_getClass (dt-ffi/string->c "NSString"))
                              :pointer
                              (sel_registerName (dt-ffi/string->c "stringWithUTF8String:"))
                              (dt-ffi/string->c ~subject))
          (set? subject) (if (seq subject)
                           `(let [v# ~(into []
                                               (map #(objc-syntax env %))
                                               (second form))
                                     len# (count v#)
                                     mem# (dtype/make-container
                                           :native-heap
                                           :int64
                                           (into []
                                                 (map #(.address (dt-ffi/->pointer %)))
                                                 v#))]
                              (objc
                               [NSSet :setWithObjects:count mem# len#]))
                           `(objc [NSSet set]))
          (map? subject) (if (seq subject)
                           `(let [m# ~(into {}
                                            (map (fn [[k v]]
                                                   [(objc-syntax env k)
                                                    (objc-syntax env v)]))
                                            (second form))
                                  len# (count m#)
                                  keys# (dtype/make-container
                                         :native-heap
                                         :int64
                                         (into []
                                               (map #(.address (dt-ffi/->pointer %)))
                                               (keys m#)))
                                  objects# (dtype/make-container
                                            :native-heap
                                            :int64
                                            (into []
                                                  (map #(.address (dt-ffi/->pointer %)))
                                                  (vals m#)))]
                              (objc
                               [NSDictionary :dictionaryWithObjects:forKeys:count objects# keys# len#]))
                           `(objc [NSDictionary dictionary]))

          (symbol? subject)
          `(cond
             (integer? ~subject) (objc [NSNumber :numberWithLong ~subject])
             (double? ~subject) (objc [NSNumber :numberWithDouble ~subject])
             (string? ~subject) (objc-msgSend
                                 (objc_getClass (dt-ffi/string->c "NSString"))
                                 :pointer
                                 (sel_registerName (dt-ffi/string->c "stringWithUTF8String:"))
                                 (dt-ffi/string->c ~subject))
             :else (throw (ex-info "Can't coerce form"
                                   {:form (quote ~form)})))

          (boolean? subject)
          (let [sel `(sel_registerName (dt-ffi/string->c "numberWithBool:"))]
            `(objc-msgSend
              (objc_getClass (dt-ffi/string->c "NSNumber"))
              :pointer
              ~sel
              ~(if subject
                 `(byte 1)
                 `(byte 0))))

          (number? subject)
          (let [sel (cond
                      (instance? Long subject) `(sel_registerName (dt-ffi/string->c "numberWithLong:"))
                      (double? subject) `(sel_registerName (dt-ffi/string->c "numberWithDouble:"))
                      :else (throw (ex-info "Unsupported number literal"
                                            {:form form})))]
            `(objc-msgSend
              (objc_getClass (dt-ffi/string->c "NSNumber"))
              :pointer
              ~sel
              ~subject))
          
          :else (throw (ex-info "Unsupported form."
                                {:form form}))))

      set! nil

      ;; assume block
      fn
      (objc-syntax-fn env form)

      clojure.core/unquote (second form)

      ;; else
      (cons verb
            (map #(objc-syntax env %) (rest form))))

    ;;else
    ;; empty list
    (throw (ex-info "Unsupported form."
                    {:form form}))))

(defn ^:private doall* [s] (dorun (tree-seq seqable? seq s)) s)

;; doall* is required since we use *sci-ctx*
;; and syntax quote can be lazy
;; must realize the whole tree to make sure
;; that *sci-ctx* uses the value available when called.
(defn objc-syntax
  ([form]
   (doall*
    (objc-syntax nil form)))
  ([env form]
   (doall*
    (cond

      (nil? form) `(ffi/long->pointer 0)

      (seq? form)
      (objc-syntax-seq env form)

      ;; (map? form)
      ;; (objc-syntax-map form)

      (vector? form) (objc-syntax-vector env form)

      (number? form) form

      (boolean? form) `(byte ~(if form
                               1
                               0))

      (symbol? form) (objc-syntax-symbol env form)

      :else (throw (ex-info "Unsupported form."
                            {:form form}))))))


(defmacro objc [form]
  (objc-syntax &env form))

(defn nsstring->str [nsstring]
  (let [p (objc [nsstring UTF8String])]
    (dt-ffi/c->string p)))

(defn str->nsstring [s]
  (objc-msgSend
   (objc_getClass (dt-ffi/string->c "NSString"))
   :pointer
   (sel_registerName (dt-ffi/string->c "stringWithUTF8String:"))
   (dt-ffi/string->c s)))

(defn oprn [os]
  (prn (nsstring->str os)))

(defn describe [o]
  (println
   (nsstring->str 
    (objc
     [o description])))
  (flush))


(comment

  (objc ^int [[NSArray array] count]))


(comment
  ;; var args not yet supported.
  ;; will crash.
  (objc ^int [[NSArray :arrayWithObjects @"one" @"two" @"three", nil] count])
  ,)




(comment
  (def predicate
    (-> (objc_getClass "NSPredicate")
        (objc-msgSend Pointer (sel_registerName "predicateWithBlock:")
                      (make-block Integer/TYPE
                                  [Pointer Pointer]
                                  (fn [a b]
                                    (println a b)
                                    1))
                      ))

    )
  

  (def my-nsstring (objc-msgSend 
                    (objc_getClass "NSString")
                    Pointer
                    (sel_registerName "stringWithUTF8String:")
                    (.getBytes "hello" "utf-8")
                    ))





  (objc-msgSend predicate Integer/TYPE (sel_registerName "evaluateWithObject:substitutionVariables:")
                ;; my-nsstring
                (objc_getClass "NSPredicate")
                (-> (objc_getClass "NSDictionary")
                    (objc-msgSend Pointer (sel_registerName "dictionaryWithObject:forKey:")
                                  (objc_getClass "NSPredicate")
                                  my-nsstring
                                  
                                  )
                    ))
  ,)



;; ;;
;; (gen/def-struct
;;   "com.phronemophobic.objcjure.structs"
;;   {:kind "CXCursor_StructDecl",
;;    :spelling "struct CGSize",
;;    :type "CXType_Record",
;;    :id :clong/CGSize,
;;    :size-in-bytes 16,
;;    :fields
;;    [{:type "double",
;;      :datatype :coffi.mem/double,
;;      :name "width",
;;      :bitfield? false,
;;      :calculated-offset 0}
;;     {:type "double",
;;      :datatype :coffi.mem/double,
;;      :name "height",
;;      :bitfield? false,
;;      :calculated-offset 64}]})
;; (import 'com.phronemophobic.objcjure.structs.CGSize)

#_(defn -main []
  ;; NSImage *image = [NSImage imageNamed:@"example.png"];
  ;;  CIImage *ciImage = [[CIImage alloc] initWithData:image.TIFFRepresentation];
  (def nsimage (objc
                [[NSImage alloc] :initWithContentsOfFile  @"input.png"]))
  (def ciimage (objc
                [[CIImage alloc] :initWithData [nsimage TIFFRepresentation]]))

  (describe ciimage )
  

  ;; CIFilter<CISepiaTone>* sepiaFilter = CIFilter.sepiaToneFilter;
  (def sepiaFilter (objc [CIFilter sepiaToneFilter]))
  (objc
   [sepiaFilter :setValue:forKey ciimage @"inputImage"])
  (objc
   [sepiaFilter :setValue:forKey @1.0 @"intensity"])

  (def output (objc
               [[sepiaFilter :valueForKey @"outputImage"] retain]))
  
  ;; sepiaFilter.inputImage = inputImage;
  ;; sepiaFilter.intensity = intensity;
  ;; return sepiaFilter.outputImage;
  
  ;;  [sepiaFilter setValue:@(1.0) forKey:kCIInputIntensityKey];


  ;; NSImage *nsImage = [[NSImage alloc] initWithCGImage:cgImage size:NSZeroSize];
  (def outnsimage (objc [[NSImage alloc] :initWithCGImage:size  output ~(CGSize.)]))

  (def image-rep (objc
                  [[NSBitmapImageRep alloc] :initWithCIImage output]))
  
  (def image-data (objc [image-rep TIFFRepresentation]))
  
  (prn "write success" (objc ^byte [image-data :writeToFile:options:error @"sepia.tiff" ~(int 1) nil]))
  
  )


(defn arc!
  "Calls retain on `o`. Registers a cleaner that calls release."
  [o]
  (objc [o :retain])
  (ffi/add-cleaner!
   o
   (let [;; Don't keep reference to `o` in cleanup function.
         address (.address (dt-ffi/->pointer o))
         description (nsstring->str (objc [o description]))]
     (fn []
       (prn "cleaning" description)
       (objc [~(ffi/long->pointer address) :release]))))
  o)
