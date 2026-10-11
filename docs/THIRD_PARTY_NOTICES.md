# Third-party notices

Lattice's own source is under the [MIT license](../LICENSE). Third-party components retain their own licenses. Distribution archives include these notices and the license texts in `licenses/` inside the plugin implementation JAR.

| Component | Distribution | License / source |
|---|---|---|
| MySQL Connector/J 26.7.0 | Plugin dependency; Gradle resolves the JAR from Maven Central | GPLv2 with additional permissions and Universal FOSS Exception 1.0; [complete vendor license](../licenses/mysql-connector-j-26.7.0-LICENSE.txt), [upstream source tag](https://github.com/mysql/mysql-connector-j/tree/26.7.0) |
| HSQLDB 2.7.4 | Plugin dependency; Gradle resolves the JAR from Maven Central | BSD-style HSQLDB/Hypersonic licenses; [license text](../licenses/hsqldb-LICENSE.txt), [source artifact](https://repo.maven.apache.org/maven2/org/hsqldb/hsqldb/2.7.4/hsqldb-2.7.4-sources.jar) |
| Protocol Buffers Java 4.36.2 | Plugin dependency, transitive from Connector/J | BSD 3-Clause; [license text](../licenses/protobuf-LICENSE.txt), [upstream source](https://github.com/protocolbuffers/protobuf/tree/v36.2) |

The release workflow also attaches the checksum-pinned, unmodified MySQL Connector/J 26.7.0 source archive alongside the plugin ZIP. Retain that corresponding-source asset and these notices when redistributing releases. Distribute locally built ZIPs with the same source archive (see [RELEASING](RELEASING.md)).

User-selected JDBC JARs and downloaded drivers have their own licensing terms. Test SDKs, database executables, Microsoft runtimes, JUnit and Plugin Verifier are stored outside the repository and are not shipped with the plugin.
