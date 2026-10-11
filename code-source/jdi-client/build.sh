#!/bin/bash
# Build jdi-client fat jar
# Output: target/jdi-client-1.0.0.jar (zero external dependencies)
# Usage (JDK 9+): java -jar target/jdi-client-1.0.0.jar [--host <host> --port <port>]
# Usage (JDK 8):  java -cp $JAVA_HOME/lib/tools.jar:target/jdi-client-1.0.0.jar \
#                     cn.ares.jvm.jdi.JdiClient [--host <host> --port <port>]

cd "$(dirname "$0")"
# -Duser.name sets the manifest's Built-By instead of leaking the local OS account
mvn clean package -q -DskipTests -Duser.name=Aresxue
if [ $? -eq 0 ]; then
    echo "Build successful: target/jdi-client-1.0.0.jar ($(du -h target/jdi-client-1.0.0.jar | cut -f1 | xargs))"
else
    echo "Build failed" >&2
    exit 1
fi
