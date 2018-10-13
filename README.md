# Claire

Claire is a VS Code extension to run your Clojure program.

You will certainly need to configure how to launch your program,
so Claire supports run configurations. Run configurations are stored
on the `.claire.edn` file under your project root directory.

### Clojure CLI
#### `deps.edn`

TODO

```clojure
{"ClojureScript Browser REPL" 
  {:args ["-m" "cljs.main" "--repl-env" "browser"]}}
```

This is a valid `.claire.edn` with a run configuration
for a ClojureScript REPL.

### Leiningen
#### `project.clj`

TODO

### Playground

Maybe you don't have a Clojure project yet, 
you're just learning the language and would like 
to write code and experiment. That's very okay too.

Claire has some built-in playground run configurations
so you can type your code, send it to the REPL and get a result back.

### REPL

Claire will create a child process to launch your Clojure program
and it will use its stdin to talk to the REPL. 
There is no network involved, it's process talking to process.

It's a very basic REPL, but it works for Clojure and ClojureScript 
and you won't have to add an extra dependency to your project.
