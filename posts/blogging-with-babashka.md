The prolific [@borkdude](https://github.com/borkdude) has inspired me to take
a shot at blogging with [babashka](https://github.com/babashka/babashka). I
am using his approach as described [here](https://blog.michielborkent.nl/migrating-octopress-to-babashka.html).

I've tweaked the code a bit so that I can run `bb watch` which
* starts an http server on port `8080` by default
* watches and compiles markdown files to html
* ensures html files have [live.js](https://livejs.com) loaded

`live.js` polls the server with `head` requests and looks at headers like `etag`, `last-modified` and `content-type` to determine if the page should be reloaded.

It was interesting to learn that the babashka [filewatcher](https://github.com/babashka/pod-babashka-filewatcher) pod is written in Rust.

This blog's source is [here](https://github.com/xfthhxk/blog).
