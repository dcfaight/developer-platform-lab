# Backend

Java 17 + Spring Boot starter. Open this directory as a Maven project in IntelliJ IDEA.

```sh
./mvnw verify
./mvnw spring-boot:run
```

On Windows use `mvnw.cmd`. The server binds to http://127.0.0.1:8080.
No API routes exist yet, so requests return 404. The context smoke test runs
without PostgreSQL or Kubernetes.

Packages reserve space for environments, operations, Kubernetes integration,
auditing, and configuration. Database connectivity, Flyway migrations, API
contracts, and workers are future work; the migration directory is empty.
