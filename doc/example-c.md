# Example C – Shutdown and Interrupt Handling

This example demonstrates how Proletarian handles graceful shutdown and job
interruption. We'll enqueue three different kinds of long-running jobs – two that
can be interrupted and one that can't – and see what happens when you press
Ctrl-C while they're running.

To run this example, you'll need the same setup as [Example A](example-a.md):

- PostgreSQL, version 9.5 or later, installed locally.
- The Clojure `clj` command-line tool, version 1.10.1.697 or later, with support
  for the -X option.
  ([Installation instructions](https://clojure.org/guides/getting_started))
- Two terminal windows.
- [The Proletarian Git repo](https://github.com/msolli/proletarian) cloned to a
  local directory: `git clone git@github.com:msolli/proletarian.git`. We'll
  assume you're in this directory going forward.
- The example database, created with `make examples.db.install` (see
  [Example A](example-a.md) for details). If you've already run other examples,
  you might want to start fresh with `make examples.db.recreate`.

### 1. Run the Queue Worker

In one of your terminal windows, run this command to start the Queue Worker:

```shell
clj -X:examples example-c.worker/run
```

It should start a process that polls the default queue for jobs every 1 second:

```
Number of jobs in :proletarian/default queue: 0
Number of jobs in proletarian.jobs table: 0
Number of jobs in proletarian.archived_jobs table: 0

Starting worker for :proletarian/default queue with polling interval 1 s
:proletarian.worker/polling-for-jobs {:worker-thread-id 1, :proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
:proletarian.worker/polling-for-jobs {:worker-thread-id 1, :proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
[...and so on, until you press Ctrl-C]
```

Leave this process running while you continue to step 2.

The worker in this example is configured with:

- **Advanced handler mode** (`:proletarian/handler-fn-mode` set to `:advanced`),
  which means the handler function receives the full job map (with metadata like
  `:proletarian.job/job-type`, `:proletarian.job/payload`, etc.) instead of
  separate job-type and payload arguments.
- A **JVM shutdown hook** (`:proletarian/install-jvm-shutdown-hook?`) that
  ensures the worker shuts down gracefully when you press Ctrl-C.
- An **on-shutdown callback** (`:proletarian/on-shutdown`) that prints a summary
  of successful and failed jobs when the worker shuts down.

The code for this can be found in
the [`example-c.worker`](https://github.com/msolli/proletarian/blob/main/examples/example_c/worker.clj)
namespace.

### 2. Enqueue a blocking job and interrupt it

In your other terminal window, run this command to enqueue a blocking job:

```shell
clj -X:examples example-c.enqueue-jobs/run-1
```

It should add a job that sleeps for 10 seconds:

```
Adding new blocking job to :proletarian/default queue:
{:job-id #uuid "...",
 :job-type :example-c.enqueue-jobs/blocking-job,
 :payload {:sleep-ms 10000, :timestamp #inst "..."}}
```

Switch to the worker terminal. You should see the worker pick up the job and
start running it:

```
:proletarian.worker/handling-job {:job-type :example-c.enqueue-jobs/blocking-job, ...}
Running job :example-c.enqueue-jobs/blocking-job. Payload:
{:sleep-ms 10000, :timestamp #inst "..."}
Sleeping 10000...
If you press Ctrl-C now, you should observe the following events:
  :proletarian.executor/shutting-down
  :proletarian.worker/job-interrupted
  :proletarian.executor/completed-shutdown

The current job (timestamp: ...) should then be picked up
again when restarting the worker process.
If you don't interrupt, then the job will finish and won't be run again.
```

Now press Ctrl-C in the worker terminal while the job is sleeping. Proletarian
will interrupt the sleeping thread and shut down gracefully. You should see
output like this:

```
:proletarian.executor/shutting-down {:proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
:proletarian.worker/job-interrupted {:job-type :example-c.enqueue-jobs/blocking-job, ...}
:proletarian.executor/completed-shutdown {:proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
```

The key thing to note here is that the blocking job was interrupted. Because the
job handler was in a blocking call (`Thread/sleep`), the interrupt signal was
able to stop it immediately. The job was **not** completed, which means it will
be picked up again the next time you start the worker.

Start the worker again to verify:

```shell
clj -X:examples example-c.worker/run
```

You should see the same job get picked up and processed to completion this time
(assuming you don't interrupt it again).

### 3. Enqueue a core.async blocking job and interrupt it

This is the same idea as step 2, but instead of `Thread/sleep`, the job blocks
on `core.async/<!!`. This demonstrates that any blocking operation that responds
to thread interrupts will behave the same way.

Enqueue a core.async blocking job:

```shell
clj -X:examples example-c.enqueue-jobs/run-2
```

It should add a job that waits on a `core.async` timeout channel for 10 seconds:

```
Adding new core.async blocking job to :proletarian/default queue:
{:job-id #uuid "...",
 :job-type :example-c.enqueue-jobs/async-blocking-job,
 :payload {:timestamp #inst "...", :wait-ms 10000}}
```

Switch to the worker terminal. You should see the worker pick up the job:

```
:proletarian.worker/handling-job {:job-type :example-c.enqueue-jobs/async-blocking-job, ...}
Running job :example-c.enqueue-jobs/async-blocking-job. Payload:
{:timestamp #inst "...", :wait-ms 10000}
Waiting on core.async/<!! for 10000 ms...
If you press Ctrl-C now, you should observe the following events:
  :proletarian.executor/shutting-down
  :proletarian.worker/job-interrupted
  :proletarian.executor/completed-shutdown

The current job (timestamp: ...) should then be picked up
again when restarting the worker process.
If you don't interrupt, then the job will finish and won't be run again.
```

Press Ctrl-C while the job is waiting. Just like with the `Thread/sleep` job,
Proletarian interrupts the blocking `<!!` call and shuts down immediately:

```
:proletarian.executor/shutting-down {:proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
:proletarian.worker/job-interrupted {...}
:proletarian.executor/completed-shutdown {:proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
```

The same thing happened: the job was interrupted and left in the queue. Start
the worker again and it will be picked up and processed to completion.

### 4. Enqueue a CPU-bound job and try to interrupt it

Now let's try with a job that can't be interrupted. Enqueue a CPU-bound job:

```shell
clj -X:examples example-c.enqueue-jobs/run-3
```

It should add a job that busy-loops for 10 seconds:

```
Adding new time-consuming job to :proletarian/default queue:
{:job-id #uuid "...",
 :job-type :example-c.enqueue-jobs/cpu-bound-job,
 :payload {:run-ms 10000, :timestamp #inst "..."}}
```

Switch to the worker terminal. You should see the worker pick up the job:

```
:proletarian.worker/handling-job {:job-type :example-c.enqueue-jobs/cpu-bound-job, ...}
Running job :example-c.enqueue-jobs/cpu-bound-job. Payload:
{:run-ms 10000, :timestamp #inst "..."}
This job is CPU-bound. We cannot interrupt/stop such a job. It will run until it
is finished, but the workers will not pick up any more jobs while it is shutting down.
Running for 10000 ms...
If you press Ctrl-C now, you should observe the following events:
  :proletarian.executor/shutting-down
  (Pause until job finishes)
  :proletarian.worker/job-finished
  :proletarian.executor/completed-shutdown
```

Now press Ctrl-C. This time, the shutdown won't be instant. Because the job is
CPU-bound (a tight loop), Java's thread interrupt mechanism can't stop it – the
thread has to finish on its own. You should see output like this:

```
:proletarian.executor/shutting-down {:proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
Done.
:proletarian.worker/job-finished {:job-type :example-c.enqueue-jobs/cpu-bound-job, ...}
:proletarian.executor/completed-shutdown {:proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
```

Notice the pause between `:proletarian.executor/shutting-down` and the job
finishing. The worker waits for the CPU-bound job to complete before shutting
down. No new jobs are picked up during this time.

### How it works

The difference between the jobs comes down to how Java thread interrupts work:

- **Blocking operations** like `Thread/sleep`, I/O reads, and `core.async/<!!`
  respond to interrupts by throwing an `InterruptedException`. Proletarian
  catches this and stops the job, leaving it in the queue for reprocessing.
  Steps 2 and 3 both demonstrate this.
- **CPU-bound operations** like tight loops don't check the interrupt flag, so
  they can't be stopped. Proletarian waits for them to finish before completing
  the shutdown. Step 4 demonstrates this.

This means that if your job handlers use blocking operations (which most
real-world jobs do – making HTTP calls, writing to databases, etc.), Proletarian
can shut down quickly and cleanly. The unfinished jobs will be picked up again
when the worker restarts, which is why your handler functions should be
idempotent.

The code for the job handlers can be found in
the [`example-c.enqueue-jobs`](https://github.com/msolli/proletarian/blob/main/examples/example_c/enqueue_jobs.clj)
namespace.
