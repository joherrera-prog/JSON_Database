# JSON Database

A concurrent TCP database and command-line client. The server persists a JSON
document and supports reading, writing, and deleting nested object properties.
This is the standalone Maven project for stage 6.

## Build and verify

Run from the directory containing `pom.xml` with JDK 17 or later.

```sh
./mvnw clean verify
```

On Windows, use `mvnw.cmd clean verify`. An installed Maven can also run
`mvn clean verify`.

| Check | Output |
| --- | --- |
| JUnit 5 and Mockito | `target/surefire-reports/` |
| JaCoCo | `target/site/jacoco/index.html` |
| Checkstyle | `target/checkstyle-result.xml` |
| PMD | `target/pmd.xml` |
| SpotBugs | `target/spotbugsXml.xml` |

The build requires at least 80% aggregate line coverage and 80% branch
coverage. Static analysis checks production code. The tests use temporary
files and simulated sockets, so they can run without opening a port.
The PMD exceptions are local to the CLI and server entry points and document
intentional console output, loopback binding, and resource ownership. The
SpotBugs filter allows the command processor to retain the shared database.

Generate HTML from the English Javadocs after building.

```sh
javadoc -private -classpath target/json-database-1.0.0-all.jar \
  -sourcepath src/main/java -d target/apidocs client server
```

Open `target/apidocs/index.html`.

## Run the application

Start the server in one terminal.

```sh
java -jar target/json-database-1.0.0-all.jar
```

In a second terminal, run the client from the same project directory.

```sh
java -cp target/json-database-1.0.0-all.jar client.Main -in table-set.json
java -cp target/json-database-1.0.0-all.jar client.Main -in table-get.json
java -cp target/json-database-1.0.0-all.jar client.Main -in table-delete.json
java -cp target/json-database-1.0.0-all.jar client.Main -in table-bill.json
java -cp target/json-database-1.0.0-all.jar client.Main -t exit
```

The first two requests store and retrieve the number `250`. Deleting the
total keeps the empty bill object. The `exit` request closes the server.
The server creates `src/server/data/db.json` at startup if it does not
already exist. Request files live in `src/client/data`.

The `-t`, `-k`, and `-v` options send string keys and values. Use `-in`
for nested paths or values such as objects, numbers, arrays, and JSON null.
