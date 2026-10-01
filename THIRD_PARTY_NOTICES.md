# Third-party notices

Titan GraphQL project-owned source uses GPL-3.0-or-later; see `LICENSE`. The pinned Titan and
Titan DSL submodules use GPL-3.0-only. Their licenses and notices remain in `vendor/titan` and
`vendor/titan-dsl`. Distributions that combine these projects use GPL version 3.

The standalone frontend and control worker ZIPs retain their dependency JARs without shading.
The ZIP contents and Gradle dependency locks define the exact redistributed artifact inventory.
The frontend release verifier rejects an unreviewed runtime closure.

The bundled libraries retain these upstream license terms and notices.

| Artifact family | License | Retained text |
| --- | --- | --- |
| Jackson | Apache-2.0 | Dependency JAR `META-INF/LICENSE` and `META-INF/NOTICE` |
| SnakeYAML | Apache-2.0 | `licenses/Apache-2.0.txt` |
| PostgreSQL JDBC | BSD-2-Clause | Dependency JAR `META-INF/LICENSE` and shaded component texts under `META-INF/licenses` |
| MySQL Connector/J | GPL-2.0 with Universal FOSS Exception | Dependency JAR `LICENSE`, including its exception and third-party notices |
| Protocol Buffers Java | BSD-3-Clause | `licenses/protobuf-BSD-3-Clause.txt`, from the upstream `v25.5` source |
| Checker Framework qualifiers | MIT | Dependency JAR `META-INF/LICENSE.txt` |

The Gradle wrapper uses Apache-2.0 and retains its upstream script headers. Its license text is
`licenses/Apache-2.0.txt`; the wrapper JAR does not enter the runtime ZIPs. Test libraries and
database/container images resolve separately and retain their own licenses. A base image's license
inventory remains the image publisher's inventory, rather than this project's source license.

Corresponding Titan GraphQL, Titan, and Titan DSL source is available by recursively cloning
`https://github.com/rbilleci/titan-graphql.git` at the distribution's release tag. Keep the recorded
submodule revisions. MySQL Connector/J's corresponding source is available at
`https://github.com/mysql/mysql-connector-j/tree/8.4.0`; retain that source when redistributing the
driver. This notice does not replace any dependency's license or grant an additional exception.
