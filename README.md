## Muguet

I'm an opinionated framework that brings the best of data driven and domain driven worlds.

I suite well for:

- Fast prototyping: start with me, and when you know better, dump me and start over.
- Exploration and implementation of your business domain with trials and errors
- Rapid Application Development for non intensively used applications

My key features:

- I model your business with more than CRUD operations
- I remember everything that happened, I delete nothing, I let you query the past
- I'm declarative first but I'm hackable programmatically.

About me:

- I'm written in Clojure (Java API soon)
- I use XTDB, Malli and Integrant as fundational libraries
- I'm a framework: I go beyond providing functions, you have to go my style
- I still don't know much about me, I'm in design phase

## Code organization

Namespaces `muguet.*` are meant to be public

- `muguet.api` contains schemas, protocols and constants
- `muguet.core` contains public functions of the framework
- `muguer.utils` contains optional various utilities

Namespaces in `muguet.internals.*` contains low-level features. You shouldn't
`require` those namespaces.

## Tests

Run `bin/kaocha`.

Append `--watch` for watch mode.