# objcjure

A clojure DSL for calling objective c code.

## Syntax

### Literals

| Clojure Type | Objective-C Type |
|--------------|-------------------|
| `nil`        | NULL (null pointer) |
| `boolean`    | BOOL                |
| `byte`       | int8                |
| `short`      | int16               |
| `int`        | int32               |
| `long`       | int64               |
| `float`      | float32             |
| `double`     | float64             |

Examples:

| Clojure       | Objective-C |
|---------------|-------------|
| `nil`         | `NULL`        |
| `true`        | `YES`         |
| `false`       | `NO`          |
| `(byte 1)`    | `1`           |
| `(short 1)`   | `1`           |
| `(int 1)`     | `1`           |
| `1`           | `1`           |
| `(float 1.0)` | `1.0`         |
| `1.0`         | `1.0`         |

#### Coercions

Coercions for common objective-c types are also provided via `@`.

| Clojure       | Objective-C |
|---------------|-------------|
| `@"mystring"` | `@"mystring"` |
| `@true`       | `@YES`      |
| `@false`      | `@NO`       |
| `@1`          | `@1`        |
| `@1.0`        | `@1.0`      |
| `@{@"key" @"value"}` | `@{@"key" @"value"}` |
| `@[@1, @2]` | `@[@1 @2 ]` |
| `@#{@1, @2}` | `[NSSet setWithArray: @[@1 @2 ]]` |

### Symbols

If a symbol can be resolved in the current context, it will use the value of the binding. Otherwise, it is assumed to be the name of a class which will be looked up at runtime.


### Function Calls

Function calls are represented by vectors (similar to how they appear in objective-c).

Some examples:

| clojure | objective-c |
|--------------|-------------------|
| `[NSArray :array]` | `[NSArray array]` |
| `[nsdict :setValue:forKey info @"nowPlayingInfo"]` | `[nsdict setValue:info forKey:@"nowPlayingInfo"]` |

Note the differences:
- the method selector is a keyword
- the method selector is the full selector (splitting up the selector into its parts to more closely match objective c may be supported in the future).
- No trailing `:` in method name!

The return type is assumed to be a pointer, but can be coerced via type hint.
```clojure

;; Return a long.
(objc ^long [[NSArray :array] :count])
;; Return void
(objc ^void [my-player :play])

;; Return a type by hinting the dtype next struct name
(objc ^cm_time [~(:player @pod-state) :currentTime])
```

The following type hints are supported:
- `byte`
- `short`
- `int`
- `long`
- `float`
- `double`
- `pointer`
- `pointer?`
- `void`
- The name (as a symbol) of a dtype next struct.


### Blocks

Anonymous functions are automatically coerced to blocks. The return value and args are assumed to be of type pointer, but can be specified via type hint.

```
(objc ^void
      [[AVAudioSession :sharedInstance]
       :activateWithOptions:completionHandler
       0
       (fn ^void [^byte activated error]
         (println "activated" activated error))])
```

### Unescape

Arbitrary clojure can be inserted anywhere using `~`.

`(objc [NSNumber :numberWithLong ~(+ 1 2 3 4)])`

## License

Copyright © 2024 Adrian

Distributed under the under Apache License v2.0.
