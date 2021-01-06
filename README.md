# Proletarian

A durable job queuing and worker system for Clojure backed by PostgreSQL.

* [Overview](#overview)
* [Usage](#usage)
* [Installation](#installation)
* [Terminology](#terminology)
* [Retries, at least once processing, and idempotence](#retries-at-least-once-processing-and-idempotence)
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

If you're already using PostgreSQL as your main database, there are some very
nice advantages to having your job queue in PostgreSQL as well:

- Using a transaction, you can atomically commit changes to the database along
  with the queueing of the job. A common use-case for this is sending an email
  after some action in the web application, e.g. when a user creates an account
  you want to send them a confirmation email. You want the creation of the
  account, and the enqueuing of the email job to either succeed or fail
  together. This is sometimes called the Outbox Pattern in distributed computing
  literature.
- Similarly, in your worker job, if you're doing some processing that involves
  writing to the database, the changes will only be committed if the job as a
  whole succeeds. The job will be removed from the queue in the same
  transaction.

## Usage

Here is basic example, showing the creation of a queue worker in one namespace,
and the enqueuing of a job in another namespace:

```clojure
(ns your-app.workers
  "You'll probably want to use a component state library (Component, Integrant,
   Mount, or some such) for managing the worker state. For this example we're
   just def-ing the worker. The worker controller constructor functions takes
   a javax.sql.DataSource as its first (and only required) argument. You
   probably already have a data-source at hand in your application already. Here
   we'll use next.jdbc to get one from a JDBC connection URL."
  (:require [next.jdbc :as jdbc]
    [proletarian.worker :as worker]))

(def email-worker
  (let [ds (jdbc/get-datasource "jdbc:postgresql://...")]
    (worker/create-worker-controller ds)))

(worker/start! email-worker)
```

```clojure
(ns your-app.handlers
  "Let's say this is a namespace where you handle web requests. We're going to
   handle the request, write something to the database, and enqueue a job.
   We'll do this in a transaction with a little bit of help from next.jdbc."
  (:require [next.jdbc :as jdbc]
    [proletarian.job :as job]))

(defn some-handler [system request]
  (jdbc/with-transaction [tx (:db system)]
    ;; Do some business logic here
    ;; Write some result to the database
    ;; Enqueue the job:
    (job/enqueue! tx ::confirmation-email
      {:email email-address, :other-data-1 :foo, :other-data-2 :bar})
    ;; Return a response
    response))

;; Implement the proletarian.job/handle! multimethod for the job type.
(defmethod job/handle! ::confirmation-email
  [context job-type {:keys [email-address other-data-1 other-data-2]}]
  ;; Send the mail and do other time-consuming work here.
  )
```

## Installation

Proletarian works with your existing PostgreSQL database. It uses
the `SKIP LOCKED`
feature [that was introduced with PostgreSQL 9.5](https://www.2ndquadrant.com/en/blog/what-is-select-skip-locked-for-in-postgresql-9-5/),
so there's a hard requirement of at least version 9.5.

Proletarian works with any other database and SQL libraries you might be using,
and does itself not depend on any such library.

You'll have to create two database tables, one for queueing jobs, and one for
keeping a record of finished jobs. These are defined in `database/tables.sql` in
this repository, along with a PostgreSQL _schema_ to contain them, and an index.
Before using the library, you must install these tables in your database. There
are many ways you can do this. You are probably already using a migration
library like [Flyway](https://flywaydb.org/) or
[Migratus](https://github.com/yogthos/migratus). Copy the contents of the
`database/tables.sql` file into a migration file. You can change the PostgreSQL
schema and table names, but then you'll need to provide the
`:proletarian/job-table` and `:proletarian/archived-job-table` options to
`create-worker-controller`.

## Examples

This repository contains a few examples that demonstrates features and usage
patterns. You can run these by cloning this repo, execute a script to set up
an example Proletarian database, and then run the examples from your 
terminal. All the details are in the example docs:

- [Example A - The Basics](./doc/example-a.md)
- [Example B - Failure and Retries](./doc/example-b.md)
- [Example C - Job Interruption and Queue Worker Shutdown](./doc/example-c.md)

## Terminology

### Queue Worker

A _queue worker_ is a process that works off a given named queue. It can have
one or more _worker threads_, working in parallel. The worker threads pick jobs
off a queue and run them. While there are jobs to be processed, the workers will
work on them continuously until the queue is empty. Then they will poll the
queue at a configurable interval.

There is a default queue, `:proletarian/default`, which is the one used by
`job/enqueue!` and `worker/create-queue-worker` if no queue is specified in the
options.

You can create as many queue workers as you like, consuming jobs from different
queues. The jobs will all live in the same table, but are differentiated by the
queue name. The parameters you provide when setting up the queue workers, like
the polling interval and the number of worker threads (ie. the number of
parallel worker instances that are polling the queue and working on jobs), will
in effect control the priority of the jobs on the different queues.

A queue worker is local to one machine only. If you have several machines acting
as job processing instances, they will each have queue worker processes running.
The parallelization factor for a given queue will be the number of queue worker
processes (on different machines) multiplied by the number of threads in each
queue worker.

### Job Handler

```clojure
(require '[proletarian.job :as job])

(defmethod job/handle! ::the-job-type
  [context job-type payload]
  ;; Do the work here.
  )

(defmethod job/retry-strategy ::the-job-type
  [job throwable]
  {:retries 4
   :delays [1000 5000]})
```

## Retries, At Least Once Processing, and Idempotence

### Shutdown and interrupts

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

1. By catching `InterruptedException`. If your job calls (directly on
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
is [an article by Brian Goetz](https://www.ibm.com/developerworks/library/j-jtp05236/index.html)
that goes into great detail on how to implement cancelable tasks.

If you have specific questions, or even advice you want to share regarding this
topic, please [open an issue](https://github.com/msolli/proletarian/issues).

## Acknowledgements

Many thanks for [Christian Johansen](https://github.com/cjohansen/) for help
with designing the library and database schema.

Hat tip to the creators of [MessageDB](https://github.com/message-db/message-db)
for inspiration how to do the example database install scripts.

## License

Copyright © 2020-2021 Martin Solli

Distributed under the Eclipse Public License, same as Clojure.
