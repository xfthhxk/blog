# blog
Source code for my [blog](https://xfthhxk.github.io/blog/index.html).


The blog is generated using babashka.


`bb watch` will launch a server on port `8080` and watch
the `posts` and `templates` directories. Babashka filewatcher pod
detects changes and triggers rendering of output html files. `live.js`
is loaded on the page and polls the server for changed files and loads them.

`bb render` will generate html from the markdown files without `live.js`.

`bb publish!` will do a clean, render and publish the blog.

## License
A big chunk of the source was adapted from [borkdude's blog](https://github.com/borkdude/blog) and
those parts are MIT Licensed.

`ring.clj` is excerpted from [ring clojure](https://github.com/ring-clojure/ring). That code is MIT Licensed.

`live.js` is MIT Licensed.

The rest is free and unencumbered software released into the public domain.
