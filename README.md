# Proletarian

[![Download Proletarian from Clojars](https://img.shields.io/clojars/v/msolli/proletarian.svg)](https://clojars.org/msolli/proletarian)
[![Read documentation at Cljdoc](https://cljdoc.org/badge/msolli/proletarian)](https://cljdoc.org/d/msolli/proletarian/CURRENT)

A durable job queuing and worker system for Clojure backed by PostgreSQL 9.5+/MySQL 8.0.1+

* [Overview](#overview)
* [Usage](#usage)
* [Installation](#installation)
* [Terminology](#terminology)
* [Retries, at least once processing, and idempotence](#at-least-once-processing-idempotence-and-retries)
* [Acknowledgements](#acknowledgements)
* [License](#license)

## Overview

Use Proletarian for asynchronously executing tasks in the background. It's
useful for offloading long-running tasks from the request thread in user-facing
web applications. What kind of tasks? Anything that uses external services, and
anything that takes more than a few milliseconds:

- sending emails
- making HTTP calls to external systems
- updating search indexes
- batch imports and exports

If you're already using PostgreSQL/MySQL as your main database, there is one very nice
advantage to having your job queue in PostgreSQL/MySQL as well:

Using a transaction, you can atomically commit changes to the database along
with the queueing of the job. A common use-case for this is sending an email
after some action in the web application, e.g., when a user creates an account
you want to send them a confirmation email. You want the creation of the
account, and the enqueuing of the email job to either succeed or fail
together. This is sometimes called the Outbox Pattern in distributed computing
literature.

## Usage

Here is a basic example, showing the creation of a queue worker in one
namespace, and the enqueuing of a job in another namespace:

```clojure
(ns your-app.workers
  "You'll probably want to use a component state library (Component, Integrant,
   Mount, or some such) for managing the worker state. For this example we're
   just def-ing the worker. The queue worker constructor function takes
   a javax.sql.DataSource as its first argument. You probably already have a
   data-source at hand in your application already. Here we'll use next.jdbc to
   get one from a JDBC connection URL.

   The second argument is the job handler function. Proletarian will invoke
   this whenever a job is ready for processing. It's a arity-2 function, with
   the job type (a keyword) as the first argument, and the job's payload as
   the second argument."
  (:require [next.jdbc :as jdbc]
            [proletarian.worker :as worker]
            [your-app.handlers :as handlers]))

(def email-worker
  (let [ds (jdbc/get-datasource "jdbc:postgresql://...")]
    (worker/create-queue-worker ds handlers/handle-job!)))

(worker/start! email-worker)
```

```clojure
(ns your-app.handlers
  "Let's say this is a namespace where you handle web requests. We're going to
   handle the request, write something to the database, and enqueue a job.
   We'll do this in a transaction with a little bit of help from next.jdbc."
  (:require [next.jdbc :as jdbc]
            [proletarian.job :as job]))

(defn some-route-handler [system request]
  (jdbc/with-transaction [tx (:db system)]
    ;; Do some business logic here
    ;; Write some result to the database
    ;; Enqueue the job:
    (job/enqueue! tx ::confirmation-email
      {:email email-address, :other-data-1 :foo, :other-data-2 :bar})
    ;; Return a response
    response))

(defmulti handle-job!
  "Since we passed this multimethod as the second argument to
  worker/create-queue-worker, it is called by the Proletarian Queue Worker when
  a job is ready for execution. Implement this multimethod for your job types."
  (fn [job-type _payload] job-type))

;; Implement the handle-job! multimethod for the job type.
(defmethod handle-job! ::confirmation-email
  [job-type {:keys [email-address other-data-1 other-data-2]}]
  ;; Send the mail and do other time-consuming work here.
  )
```

### Logging

Proletarian does not depend on a logging framework, and has no opinions on how you should log in your application.
The `:proletarian/log` option to `create-queue-worker` specifies a function that is called by the Queue Worker when
anything interesting and log-worthy happens during operation. It takes two arguments: The first is a keyword
identifying the event being logged. The second is a map with data describing the event.

If you do not specify a logging function, the default is simply a `println`-logger that will print every event using
`println`.

There is no "severity" or "level" information included with the log events. Every application will have different
requirements here. A sensible default might be something like this (using `clojure.tools.logging`):
```clojure
(ns your-app.workers
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [proletarian.worker :as worker]
            [your-app.handlers :as handlers]))

(defn log-level
  [x]
  (case x
    ::worker/queue-worker-shutdown-error :error
    ::worker/handle-job-exception-with-interrupt :error
    ::worker/handle-job-exception :error
    ::worker/job-worker-error :error
    ::worker/polling-for-jobs :debug
    :proletarian.retry/not-retrying :error
    :info))

(defn logger
  [x data]
  (log/logp (log-level x) x data))

(def worker
  (let [ds (jdbc/get-datasource "jdbc:postgresql://...")]
    (worker/create-queue-worker ds handlers/handle-job! {:proletarian/log logger})))
```

## Installation

Add Proletarian to your [`deps.edn`](https://clojure.org/guides/deps_and_cli)
file:
```clojure
msolli/proletarian {:mvn/version "1.0.89-alpha"}
```

Or to your [`project.clj`](https://github.com/technomancy/leiningen/blob/stable/sample.project.clj) for Leiningen:
```clojure
[msolli/proletarian "1.0.89-alpha"]
```

Proletarian works with your existing PostgreSQL/MySQL database. It uses
the `SKIP LOCKED` feature [that was introduced with PostgreSQL
9.5](https://www.2ndquadrant.com/en/blog/what-is-select-skip-locked-for-in-postgresql-9-5/),
so there's a hard requirement of at least version 9.5. With regard to MySQL,
the `SKIP LOCKED` feature was [added to MySQL
8.0.1](https://dev.mysql.com/blog-archive/mysql-8-0-1-using-skip-locked-and-nowait-to-handle-hot-rows/),
thusly you must be running MySQL 8.0.1 and above to avail of this library if
you are using MySQL in your stack.

Proletarian works with any Clojure database library
([next.jdbc](https://github.com/seancorfield/next-jdbc),
[clojure.java.jdbc](https://github.com/clojure/java.jdbc)) you might be using,
and does not itself depend on any such library.

You'll have to create two database tables, one for queueing jobs, and one for
keeping a record of finished jobs.

For PostgreSQL:

These are defined in [`database/postgresql/tables.sql`
in this repository](./database/postgresql/tables.sql), along with a PostgreSQL
[_schema_](https://www.postgresql.org/docs/current/ddl-schemas.html) to contain
them, and an index.

For MySQL:

These are defined in [`database/mysql/tables.sql`
in this repository](./database/mysql/tables.sql), along with a MySQL
[_schema_] to contain them, and an index.

Before using the library, you must install these tables in your database. There
are many ways you can do this. You are probably already using a migration
library like [Flyway](https://flywaydb.org/) or
[Migratus](https://github.com/yogthos/migratus).

Copy the contents of the `database/postgresql/tables.sql` or
`database/mysql/tables.sql` file into a migration file. You can change the
PostgreSQL/MySQL schema and table names, but then you'll need to provide the
`:proletarian/job-table` and `:proletarian/archived-job-table` options to
`create-queue-worker` and `enqueue!`.

## Examples

This repository contains a few examples that demonstrates features and usage
patterns. You can run these by cloning this repo, execute a script to set up an
example Proletarian database, and then run the examples from your terminal. All
the details are in the example docs:

- [Example A - The Basics](./doc/example-a.md)
- Example B - Failure and Retries - not documented yet, but fully functional
  (source: [examples/example_b](./examples/example_b))
- Example C - Job Interruption and Queue Worker Shutdown - not documented yet,
  but fully functional (source:
  [examples/example_c](./examples/example_c))

## Terminology

### Queue Worker

A _queue worker_ is a process that works off a given named queue. It can have
one or more _worker threads_, working in parallel. The worker threads pick jobs
off a queue and run them. While there are jobs to be processed, the workers will
work on them continuously until the queue is empty. Then they will poll the
queue at a configurable interval.

There is a default queue, `:proletarian/default`, which is the one used by
`job/enqueue!` and `worker/create-queue-worker` if you don't specify a queue in
the options.

You can create as many queue workers as you like, consuming jobs from different
queues. The jobs will all live in the same table, but are differentiated by the
queue name. The parameters you provide when setting up the queue workers, like
the polling interval, and the number of worker threads (i.e., the number of
parallel worker instances that are polling the queue and working on jobs), will
in effect control the priority of the jobs on the different queues.

A queue worker is local to one machine only. If you have several machines acting
as job processing instances, they will each have a queue worker process running.
The parallelization factor for a given queue will be the number of queue worker
processes (on different machines) multiplied by the number of threads in each
queue worker.

### Job Handler

The _job handler_ is the function that the Proletarian queue worker invokes when
it pulls a job off the queue. You implement this function and pass it to
`worker/create-queue-worker` when setting up the Queue Worker.

The function is invoked with two arguments:
* `job-type` – the job type as a Clojure keyword (as provided to
`job/enqueue!`).
* `payload` – the job's payload (again, as provided to `job/enqueue!`)

Your handler function must itself handle the logic of dispatching the different
job types to appropriate handler functions (see
[examples/example_c](./examples/example_c) for an example of this). It's
also useful to have system state available in this function. It should close
over references to stateful objects and functions that you need for the job to
do its work. Examples of this could be things like database and other
(Elasticsearch, Redis) connections, and runtime configuration.

```clojure
(require '[proletarian.job :as job])

(defn do-something! [db-conn foo]
  ;; Do stuff here
  ;; Enqueue a job:
  (job/enqueue! db-conn ::job-type-foo foo)
  )

;; Pass this function as the second argument to
;; proletarian.worker/create-queue-worker
(defn handle-job!
  [job-type payload]
  ;; Do the dispatch of job types here. This could maybe invoke a multimethod
  ;; that dispatches on `job-type`. See Example C.
  ;; The value of payload is whatever was passed as third argument to
  ;; job/enqueue! (the value of foo in do-something! in this case).
  )
```

## At Least Once Processing, Idempotence, and Retries

Proletarian goes to great lengths to ensure that no jobs are lost due to
exceptions, network errors, database failure, computers catching fire or other
facts of life. It relies on PostgreSQL/MySQL transactions to protect the
integrity of the jobs tables while polling and running jobs. A job will not be
removed from the queue until it has finished successfully. It is moved to the
archive table in the same transaction.

The guarantee is that Proletarian will run each job _at least once_. There are
failure scenarios where a job can run and finish successfully, but the database
operations in the transaction that moves the job off the queue can't be
completed. These are unlikely events, like the database going offline at just
the moment before the job was to be moved to the archive table. Should this
happen, the job will get picked up and run again when the queue worker comes
online again (or by a different thread or machine, depending on your setup).

The flip side of the _at least once_ guarantee is that your job handler must be
idempotent, or your business rules must tolerate that the effects of your job
handler happen more than once (in some unlikely cases).

### Retries

Jobs that throw an exception (a `java.lang.Exception` or subclass, but not other
instances of `Throwable`) will be retried according to their _retry strategy_.
The default retry strategy is to not retry.

To define a retry strategy for your jobs, you provide the `:retry-strategy-fn`
option to [`proletarian.worker/create-queue-worker`](https://cljdoc.org/d/msolli/proletarian/CURRENT/api/proletarian.worker#create-queue-worker).
This function should return the retry strategy, which is a map with the keys
`:retries` and `:delays`. See below for an explanation.
The Queue Worker calls this function when an exception is caught for the job
handler. The job and the exception are passed as arguments. You can use these
to make informed decisions about how to proceed. The exception might for
example contain information on when to retry an HTTP call (from a [Retry-After](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Retry-After)
HTTP header). [Example B](examples/example_b) implements something like
this. In most cases, however, a simple static retry strategy will suffice.

The default value for the `:retry-strategy-fn` option, which is used if you
don't specify one, simply returns `nil`, which means no retries.

```clojure
(require '[proletarian.job :as job])

(defn handle-job!
  [job-type payload]
  ;; Do stuff that might throw an exception here
  )

(defn my-retry-strategy
  [job throwable]
  {:retries 4
   :delays [1000 5000]}
  ;; This retry strategy specifies that the job should be retried up to four
  ;; times, for a total of five attempts. The first retry should happen
  ;; one second after the first attempt failed. The remaining attempts should
  ;; happen five seconds after the previous attempt failed.
  ;; After four retries, if the job was still failing, it is not retried
  ;; anymore. It is moved to the archived-job-table with a failure status.
  )
```

#### Retry Strategy

When a job throws an exception, it is caught by the Proletarian Queue Worker.
The function given as the `:retry-strategy-fn` option is then called with the
job payload and the caught exception. You can implement this function, and
based on the information in the job payload and exception, have it retry any
way you want.

It should return a map that specifies the retry strategy:
* `:retries` – the number of retries (note that the total number of attempts
will be one larger than this number).
* `:delays` – a vector of numbers of milliseconds to wait between retries. The
last number specifies the wait time for the remaining attempts if the number of
retries is larger than the number of items in the vector.

Do consider the polling interval and the job queue contention when planning
your retry strategy. The retry delay should be thought of as the earliest
time that the job will be retried. The actual retry time might be a little,
or much, later, depending on the polling interval and what other jobs are in
the queue before this one.

Examples:
```clojure
{:retries 2
 :delays [1000 5000]}
```
This will retry two times. The first time after 1 second and the second
after 5 seconds.

```clojure
{:retries 4
 :delays [2000 10000]}
```
This will retry four times. The first time after 2 seconds, the second after 10
seconds, the third after another 10 seconds, and the fourth after yet another
10 seconds.

### Failed Jobs

When there are no more retries for a job, it is moved to the archive table with
the status set to `:failure`. The function given as the `:failed-job-fn` is
then called with the job data and the caught exception.

Implement this function if you want to take action when jobs fail. It could
for example mean logging with an elevated level to make sure it is noticed by
your monitoring, or setting some entity's state to `:failed` in your domain.

### Shutdown and Interrupts

The _queue worker_, once started, will run until its `stop!` function is called.
You should call this when you want to bring down your system. If you set the
`install-jvm-shutdown-hook?` option to true, Proletarian will install a JVM
shutdown hook using `java.lang.Runtime.addShutdownHook`
that will call the `stop!` function.

When the shutdown sequence starts, threads in the queue worker thread pool will
receive an interrupt. How these interrupts are handled depends on where in the
poll/run cycle each worker thread is. If a worker thread is polling, it will
simply stop polling. If the worker thread is busy running a job, it's the job's
responsibility to handle interrupts.

Most of the time, if you have jobs that run quickly (less than a few seconds),
you can simply ignore interrupts. Your job will finish, and the worker thread
will not pick up any more jobs.

On the other hand, if your job takes a long time to finish, you should handle
interrupts one of two ways:

1. By catching `InterruptedException`. If your job calls (directly or
   indirectly) a method that throws `InterruptedException`
   (such as `Object.wait`, `Thread.sleep`, and `Thread.join`), you can wrap your
   job handler code in a try/catch that catches `InterruptedException`. Do this
   if you can meaningfully finish the job this way, or if you don't want the job
   to be run again when the queue worker starts up again.
2. By regularly checking the thread's interrupt status
   using `Thread.isInterrupted()`. If you have CPU-intensive work that takes a
   lot of time, you could chunk the work such that you can read the interrupt
   status in-between chunks, and finish the job gracefully if interrupted.

In general, if you stick to enqueuing jobs that require only a few seconds to
run and that are safe to be run again (they are _idempotent_), then you can
ignore interrupts.

There is lots more to be said about this topic. There
is [an article by Brian Goetz](https://web.archive.org/web/20201111190527/https://www.ibm.com/developerworks/library/j-jtp05236/index.html)
that goes into great detail on how to implement cancelable tasks.

If you have specific questions, or even advice you want to share regarding this
topic, please [open an issue](https://github.com/msolli/proletarian/issues).

## Acknowledgements

Many thanks for [Christian Johansen](https://github.com/cjohansen/) for help
with designing the library and database schema.

Hat tip to the creators of [MessageDB](https://github.com/message-db/message-db)
for how to do the example database install scripts.

## License

MIT License

Copyright (c) 2020-2025 Martin Solli

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
