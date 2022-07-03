## Muguet

Muguet aims to be an opinionated framework that brings the best of data driven and domain driven worlds to build the 90%
of applications out there.

Status of the project: design phase

## Code organization

Namespaces `muguet.*` are meant to be public

- `muguet.api` contains schemas, protocols and constants
- `muguet.core` contains public functions of the framework
- `muguer.utils` contains optional various utilities

Namespaces in `muguet.internals.*` contains low-level features. You shouldn't
`require` those namespaces unless public namespaces doesn't suit you. If that's the case, please report an issue.

## Tests

Run `bin/kaocha`.

Append `--watch` for watch mode.