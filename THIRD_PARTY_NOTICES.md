# Third-party notices

Lattice's own source is under the [MIT license](LICENSE). Third-party components retain their own licenses. Distribution archives include these notices and the license texts in `licenses/` inside the plugin implementation JAR.

| Component | Distribution | License / source |
|---|---|---|
| MySQL Connector/J 9.0.0 | Plugin dependency; fallback JAR in `lib/` | GPLv2 with additional permissions and Universal FOSS Exception 1.0; [complete vendor license](licenses/mysql-connector-j-9.0.0-LICENSE.txt), [upstream source tag](https://github.com/mysql/mysql-connector-j/tree/9.0.0) |
| HSQLDB 2.7.3 | Plugin dependency; fallback JAR in `lib/` | BSD-style HSQLDB/Hypersonic licenses; [license text](licenses/hsqldb-LICENSE.txt), [source artifact](https://repo.maven.apache.org/maven2/org/hsqldb/hsqldb/2.7.3/hsqldb-2.7.3-sources.jar) |
| Protocol Buffers Java 4.26.1 | Gradle distribution, as a Connector/J dependency | BSD 3-Clause; [license text](licenses/protobuf-LICENSE.txt), [upstream source](https://github.com/protocolbuffers/protobuf/tree/v26.1) |

The release workflow also attaches the checksum-pinned, unmodified MySQL Connector/J 9.0.0 source archive alongside the plugin ZIP. Retain that corresponding-source asset and these notices when redistributing releases. Distribute locally built ZIPs with the same source archive (see [RELEASING](RELEASING.md)).

User-selected JDBC JARs and downloaded drivers have their own licensing terms. Test SDKs, database executables, Microsoft runtimes, JUnit and Plugin Verifier are stored outside the repository and are not shipped with the plugin.
