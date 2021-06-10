# Changelog

## 1.0.41-alpha - 2021-06-10
### Added
* Extracted `proletarian.worker/process-next-job!` function. This function is part of the internal machinery of the
  Proletarian worker, but is being exposed as a public function for use in testing scenarios and in the REPL.

## 1.0.38-alpha - 2021-06-04
### Changed
* `handle-job!` is now a function that must be passed as second argument to
  `create-queue-worker`. The `proletarian.job/handle-job!` multimethod has been
  replaced by this.

## 1.0.32-alpha - 2021-03-20
### Added
* Lots of documentation updates.
### Changed
* The `:now-fn` option to `enqueue!` has been removed in favor of a `:clock`
option, mirroring the `create-queue-worker` options.

## 1.0.21-alpha - 2021-02-07

Initial public release.