# Example A - The Basics

This example demonstrates a Queue Worker process that polls the default queue
for jobs, and how to enqueue jobs for this Queue Worker.

To run this example, you'll need:

- PostgreSQL, version 9.5 or later, installed locally.
- The Clojure `clj` command-line tool, version 1.10.1.697 or later, with support
  for the -X option.
  ([Installation instructions](https://clojure.org/guides/getting_started))
- Two terminal windows.
- [The Proletarian Git repo](https://github.com/msolli/proletarian) cloned to a
  local directory: `git clone git@github.com:msolli/proletarian.git`. We'll
  assume you're in this directory going forward.

All right, let's get started.

### 1. Create the example database

There is a Makefile target for creating the example database. Run it with
`make examples.db.install`. Here's how it looks:

```
$ make examples.db.install
DATABASE_NAME=proletarian ./database/install.sh

Installing Proletarian Database
= = =

Creating User
- - -
» proletarian role

Creating Database
- - -
» proletarian database

Creating Schema, Tables, and Index
» proletarian schema
» proletarian.job table
» proletarian.archived_job table
» proletarian.job_queue_process_at index

Granting Privileges
- - -
» schema privileges
» table privileges

= = =
Done Installing Proletarian Database
```

You can uninstall the example database with `make examples.db.uninstall`, and
re-create it with `make examples.db.recreate`.

(Please note that while the database install script allows for customization of
the database name, the examples assume that the default (`proletarian`)
is used.)

### 2. Run the Queue Worker

In one of your terminal windows, run this command to start the Queue Worker:
```
$ clj -X:examples:example-a example-a.worker/run
```

It should start a process that polls the default queue for jobs every 5 seconds:

```
$ clj -X:examples:example-a example-a.worker/run
Number of jobs in :proletarian/default queue: 0
Number of jobs in proletarian.jobs table: 0
Number of jobs in proletarian.archived_jobs table: 0

Starting worker for :proletarian/default queue with polling interval 5 s
:proletarian.worker/polling-for-jobs {:worker-thread-id 1, :proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
:proletarian.worker/polling-for-jobs {:worker-thread-id 1, :proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
[...and so forth, until you press Ctrl-C]
```

Leave this process running while you continue to step 3.

What is happening here, is that we've started a Queue Worker thread pool in a
JVM process that continuously polls the jobs table for new work to be done. The
polling interval is configurable - in this example it is 5 seconds. (In a
production system you'd probably use a much smaller polling interval - the 
default is 100 ms. For this example, though, it is much easier to see what's
going on with a larger polling interval.)

The code for this can be found in
the [`example-a.worker`](https://github.com/msolli/proletarian/blob/main/examples/a/example_a/worker.clj)
namespace.

### 3. Enqueue a job

In your other terminal window, run this command to enqueue a job:
```
$ clj -X:examples:example-a example-a.enqueue-jobs/run
```

It should add a job to the default queue and print the job details:

```
$ clj -X:examples:example-a example-a.enqueue-jobs/run
Number of jobs in :proletarian/default queue: 0
Number of jobs in proletarian.jobs table: 0
Number of jobs in proletarian.archived_jobs table: 0

Adding new job to :proletarian/default queue:
{:job-id #uuid "b78295d5-9241-45c1-8288-e4cf128d6bea",
 :job-type :example-a.enqueue-jobs/echo,
 :payload {:message "Hello world!",
           :timestamp #inst "2021-01-06T20:43:42.451299Z"}}
```

You should see this job get picked up by the queue worker process in the other
terminal window. You'll see output like this:

```
:proletarian.worker/handling-job {:job-id #uuid "b78295d5-9241-45c1-8288-e4cf128d6bea", :job-type :example-a.enqueue-jobs/echo, :attempt 1, :worker-thread-id 1, :proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
Running job :example-a.enqueue-jobs/echo. Payload:
{:message "Hello world!", :timestamp #inst "2021-01-06T20:43:42.451Z"}
:proletarian.worker/job-finished {:job-id #uuid "b78295d5-9241-45c1-8288-e4cf128d6bea", :job-type :example-a.enqueue-jobs/echo, :attempt 1, :worker-thread-id 1, :proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
```

Run the same command again to see the miracle unfold one more time.

Things to note:

- The job is assigned a `job-id` UUID. You shouldn't need to keep track of this
  id, it's just the unique id of this job.
- The job has a `:job-type`, which is a Clojure keyword. This keyword is what
  determines which handler function Proletarian will invoke for this job. You
  need to implement the `proletarian.job/handle-job!` multimethod for each job
  type you want to handle. A common practice is to use a namespaced keyword with
  shorthand notation for the current namespace (the double colon: `::`)
  as your job type. In this example we use `::echo` in the code, which expands
  to `:example-a.enqueue-jobs/echo`, as seen in the output above.
- The payload is serialized
  using [Transit](https://github.com/cognitect/transit-clj), and stored in
  a `TEXT` column in the database. Proletarian de-serializes the payload and
  passes it to the handler. The serializer is configurable - just implement
  the `proletarian.protocols/Serializer` protocol and pass an instance of this
  under the `:proletarian/serializer` option to
  `proletarian.job/enqueue!` and `proletarian.worker/queue-worker-controller`.