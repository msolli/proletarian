# Changelog

## [1.0.109-alpha](https://github.com/msolli/proletarian/compare/v1.0.89-alpha...1.0.109-alpha)

### Changed

* [CONDITIONALLY BREAKING] Timestamps are stored as UTC regardless of JVM's time
  zone ([c10bfc9](https://github.com/msolli/proletarian/commit/c10bfc94d1d6239dd8e98d5c44d0f66fa4b04780)).
* [CONDITIONALLY BREAKING] JobIdStrategy protocol is introduced to support any
  job-id type ([8b71d06](https://github.com/msolli/proletarian/commit/8b71d06b3a959acb4a0f4766ce8bfbfbb9c5db4c)).

#### Timestamps as UTC

**NOTE:** This change is breaking only if you have workers or job-enqueueing code running in time zones other than UTC.
If unsure, check by logging the output of `(java.time.ZoneId/systemDefault)`.

**There is no need to migrate existing jobs if your infrastructure is already running in UTC.** The rest of this section
only applies if you have workers or job-enqueueing code running in time zones other than UTC.

If you have or enqueue jobs when this version of Proletarian goes to production, they may be processed earlier or later
than intended depending on your time zone offset. You will have to decide if this is acceptable for your application. If
you need precise migration, you can update existing jobs with SQL:

**PostgreSQL:**

```sql
-- Example for Europe/Oslo (UTC+2 in summer)
-- Adjust the timezone to match your current system timezone
UPDATE proletarian.job
SET process_at = (process_at AT TIME ZONE 'Europe/Oslo') AT TIME ZONE 'UTC';
```

**MySQL:**

```sql
-- Example for Europe/Oslo (UTC+2 in summer)
-- Adjust the timezone to match your current system timezone
UPDATE proletarian.job
SET process_at = CONVERT_TZ(process_at, 'Europe/Oslo', 'UTC');
```

If you need help managing the transition to UTC, please [open an issue](https://github.com/msolli/proletarian/issues) or
post a message in [#proletarian](https://clojurians.slack.com/archives/C01UG9GMVBJ) in the Clojurians Slack community.

#### JobIdStrategy protocol

Proletarian was inflexible in its handling of jobs ids. Only UUIDs were accepted, and there was no support for
database-generated job ids. A `UuidSerializer` protocol handled the serialization and deserialization of UUIDs (required
for MySQL support).

This new change introduces the `JobIdStrategy` protocol, which makes job id handling user-configurable. Its methods
`generate-id`, `encode-id` and `decode-id` together encapsulates job id behavior. Default implementations for UUIDs with
PostgreSQL and MySQL are provided.

This is a breaking change for anyone that uses the `:proletarian/uuid-serializer` config option. This option is likely
used only by MySQL users.

To upgrade, replace with `:proletarian/job-id-strategy (job-id-strategies/->mysql-uuid-strategy)`.

## [1.0.89-alpha](https://github.com/msolli/proletarian/compare/v1.0.86-alpha...1.0.89-alpha) - 2024-11-04

### Added
* :proletarian/handler-fn-mode option for setting :advanced mode ([#16](https://github.com/msolli/proletarian/issues/16))


## [1.0.86-alpha](https://github.com/msolli/proletarian/compare/v1.0.68-alpha...v1.0.86-alpha) - 2024-10-23

### Added
* Support for MySQL 8.0.1+ and above
* Documentation updates
* Type hints to avoid reflection warnings


## [1.0.68-alpha](https://github.com/msolli/proletarian/compare/v1.0.54-alpha...v1.0.68-alpha) - 2022-05-24

### Added
* `on-polling-error` option ([#8](https://github.com/msolli/proletarian/issues/8))
* `failed-job-fn` option for handling failed jobs ([9d50f2a1](https://github.com/msolli/proletarian/commit/9d50f2a18d0cce33852f47530679a7fd48777dba))
* Enqueue jobs in the future ([#6](https://github.com/msolli/proletarian/issues/6))

### Changed
* Updated dependencies ([28e042d3](https://github.com/msolli/proletarian/commit/28e042d3b910c681bfb56868f6fe60a6b06e7b08))


## [1.0.54-alpha](https://github.com/msolli/proletarian/compare/v1.0.41-alpha...v1.0.54-alpha) - 2021-12-02

### Changed
* [BREAKING] Removed `proletarian.job/retry-strategy` multimethod and added `:proletarian/retry-strategy-fn` option for
  `proletarian.worker/create-queue-worker` ([22a86fd8](https://github.com/msolli/proletarian/commit/22a86fd816402ffdafa2a2c0ecdf573087d648ad))
* Made logger function 2-arity ([1df500e9](https://github.com/msolli/proletarian/commit/1df500e9100c37a507b1acfa396a5b43539dca19))
* Updated Postgres JDBC driver ([cb8d4427](https://github.com/msolli/proletarian/commit/cb8d4427d2ce36bac2557d495c066e429fbd7303))
* Moved to the MIT license (see https://www.juxt.pro/blog/prefer-mit)

### Fixed
* Handle interrupted status also when non-InterruptedException exception is caught ([c83dc535](https://github.com/msolli/proletarian/commit/c83dc535d1e1c25a04e46bb9088c741fa8fb41a4))
* Don't stop Queue Worker on SQLTransientException (#5)

### Added
* Logging instructions in README ([fdcc4e91](https://github.com/msolli/proletarian/commit/fdcc4e917d948cd99cabf01bcd965d45a6b9565a), [dd7156c5](https://github.com/msolli/proletarian/commit/dd7156c57f2033f9864edef95bd0c349dfb58e29))


## [1.0.41-alpha](https://github.com/msolli/proletarian/compare/v1.0.38-alpha...v1.0.41-alpha) - 2021-06-10

### Added
* Extracted `proletarian.worker/process-next-job!` function. This function is part of the internal machinery of the
  Proletarian worker, but is being exposed as a public function for use in testing scenarios and in the REPL. ([#2](https://github.com/msolli/proletarian/issues/2))


## [1.0.38-alpha](https://github.com/msolli/proletarian/compare/v1.0.32-alpha...v1.0.38-alpha) - 2021-06-04

### Changed
* `handle-job!` is now a function that must be passed as second argument to `create-queue-worker`. The
  `proletarian.job/handle-job!` multimethod has been replaced by this.


## [1.0.32-alpha](https://github.com/msolli/proletarian/compare/v1.0.21-alpha...v1.0.32-alpha) - 2021-03-20

### Added
* Lots of documentation updates.

### Changed
* The `:now-fn` option to `enqueue!` has been removed in favor of a `:clock` option, mirroring the `create-queue-worker`
  options.


## 1.0.21-alpha - 2021-02-07

Initial public release.
