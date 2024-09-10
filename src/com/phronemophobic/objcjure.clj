(ns com.phronemophobic.objcjure
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [com.phronemophobic.clj-libffi :as ffi]
            [com.phronemophobic.clong.gen.dtype-next :as gen]
            [tech.v3.datatype.ffi :as dt-ffi])
  (:import
   java.io.PushbackReader)
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

#_(def ^:private main-class-loader @clojure.lang.Compiler/LOADER)
#_(deftype GenericCallback [return-type parameter-types callback]
  com.sun.jna.CallbackProxy
  (getParameterTypes [_]
    (into-array Class parameter-types))
  (getReturnType [_]
    return-type)
  (callback [_ args]
    (.setContextClassLoader (Thread/currentThread) main-class-loader)

    (import 'com.sun.jna.Native)
    ;; https://java-native-access.github.io/jna/4.2.1/com/sun/jna/Native.html#detach-boolean-
    ;; for some other info search https://java-native-access.github.io/jna/4.2.1/ for CallbackThreadInitializer

    ;; turning off detach here might give a performance benefit,
    ;; but more importantly, it prevents jna from spamming stdout
    ;; with "JNA: could not detach thread"
    (com.sun.jna.Native/detach false)
    (let [ret (apply callback args)]
      
      ;; need turn detach back on so that
      ;; we don't prevent the jvm exiting
      ;; now that we're done
      (try
        (com.sun.jna.Native/detach true)
        (catch IllegalStateException e
          nil))
      ret)))

;; ;; - block support
;; ;;    - https://clang.llvm.org/docs/Block-ABI-Apple.html
;; ;;    - https://www.galloway.me.uk/2012/10/a-look-inside-blocks-episode-1/
#_(defn make-block [return-type parameter-types callback]
  (let [jna-callback (ref! (->GenericCallback return-type parameter-types callback)) 

        block-descriptor (Block_descriptor_1ByReference.)
        block-descriptor (.writeField block-descriptor "size" (long (.size block-descriptor)))

        block (doto (Block_literal_1ByReference.)
                (.writeField "isa" (.getGlobalVariableAddress ^NativeLibrary @process-lib "_NSConcreteGlobalBlock"))
                (.writeField "flags" (int (bit-or
                                           BLOCK_IS_GLOBAL
                                           BLOCK_HAS_STRET)))
                (.writeField "invoke" (CallbackReference/getFunctionPointer jna-callback))
                (.writeField "descriptor" block-descriptor))]
    block))


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
  ;; check local env
  ;; check resolve
  ;; -> spit out code for creating class
  (when (< (count form)
           2)
    (throw (ex-info "Vectors must have at least two elements."
                    {:form form})))
  (let [tag (or (prim->dtype (-> form meta :tag))
                :pointer)]
    `(objc-msgSend
      ~(objc-syntax env (first form))
      ~tag
      ~@(if (= 2 (count form))
          [`(sel_registerName (dt-ffi/string->c ~(str (name (second form)))))]
          (eduction
           (map (fn [form]
                  (if (keyword? form)
                    `(sel_registerName (dt-ffi/string->c ~(str (name form) ":")))
                    (objc-syntax env form)))
                (rest form)))))))

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
                                     mem# (Memory. (* 8 len#))]
                                 (doseq [i# (range len#)]
                                   (.setPointer mem# (* 8 i#) (nth v# i#)))
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
                                  mem# (Memory. (* 8 len#))]
                              (doseq [i# (range len#)]
                                (.setPointer mem# (* 8 i#) (nth v# i#)))
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
                                  keys# (Memory. (* 8 len#))
                                  objects# (Memory. (* 8 len#))]
                              (doseq [[i# [k# v#]] (map-indexed vector m#)]
                                (.setPointer keys# (* 8 i#) k#)
                                (.setPointer objects# (* 8 i#) v#))
                              (objc
                               [NSDictionary :dictionaryWithObjects:forKeys:count objects# keys# len#]))
                           `(objc [NSDictionary dictionary]))
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

      fn nil

      clojure.core/unquote (second form)

      ;; else
      (cons verb
            (map #(objc-syntax env %) (rest form))))

    ;;else
    ;; empty list
    (throw (ex-info "Unsupported form."
                    {:form form}))))

(defn objc-syntax
  ([form]
   (objc-syntax nil form))
  ([env form]
   (cond

     (nil? form) (ffi/long->pointer 0)

     (seq? form)
     (objc-syntax-seq env form)

     ;; (map? form)
     ;; (objc-syntax-map form)

     (vector? form) (objc-syntax-vector env form)

     (number? form) form

     (symbol? form) (objc-syntax-symbol env form)

     :else (throw (ex-info "Unsupported form."
                           {:form form})))))


(defmacro objc [form]
  (objc-syntax &env form))

(defn nsstring->str [nsstring]
  (let [p (objc [nsstring UTF8String])]
    (dt-ffi/c->string p)))

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


