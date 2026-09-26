# MediaHandler — notes for Claude

## Finishing a task
When a requested change is done (built and tested), always ask: "Should I push this?" Pushing to `main` triggers the release workflow, which builds the jar the deployed instance updates to via the update button in the Settings UI. Don't push without a yes.

## Building
The project targets JDK 21 (`.sdkmanrc`). Build with `JAVA_HOME=~/.sdkman/candidates/java/21-tem ./mvnw ...` — under the newer default JDK, Lombok's annotation processing breaks and the compile fails with hundreds of misleading "cannot find symbol" errors.
