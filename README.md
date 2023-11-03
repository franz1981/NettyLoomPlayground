# Netty HTTP Loom Playground

This is a simple example of a Netty-based HTTP server which can dispatch requests to a virtual thread pool.

## Prerequisites

Before running the Netty server, make sure you have the following:

- Java Development Kit (JDK) 21 or later
- Maven (for building the project)

## Getting Started

To run the Netty server, follow these steps:

1. Compile it using Maven:

    ```bash
    ./mvnw clean package
    ```
2. Run the server:

    ```bash
    # by default eventLoopThreads is 1 already
    java -jar -DeventLoopThreads=1 target/netty-http-jar-with-dependencies.jar
    ``` 
3. You can opt to not run the server and run the provided benchmarking script from the `scripts` folder or run `curl` against the already running instance:
   
    ```bash
    cd scripts
    ./benchmark.sh 
    ```
    or
    ```bash
    curl -v http://localhost:8080
    ```
   
The benchmarking script have a built-in help to show you the available options:

```bash
$ ./benchmark.sh -h
Syntax: benchmark [OPTIONS]
options:
h    Display this guide.

e    event to profile, if supported e.g. -e cpu 
     check https://github.com/jvm-profiling-tools/async-profiler#profiler-options for the complete list
     default is cpu

f    output format, if supported by the profiler. e.g. async-profiler support html,jfr,collapsed
     default is html

d    duration of the load generation phase, in seconds
     default is 20

j    if specified, it uses JFR profiling. async-profiler otherwise.

t    number of I/O threads of the server application.

     default is 1

c    number of connections used by the load generator.
     default is 100

p    if specified, run perf stat together with the selected profiler. Only GNU Linux.
```