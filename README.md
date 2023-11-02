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
    mvn clean package
    ```
2. Run the server:

    ```bash
    # by default eventLoopThreads is 1 already
    java -jar -DeventLoopThreads=1 target/netty-http-jar-with-dependencies.jar
    ``` 
3. Run the provided benchmarking script from the `scripts` folder or just `curl`:

    ```bash
    curl -v http://localhost:8080
    ```