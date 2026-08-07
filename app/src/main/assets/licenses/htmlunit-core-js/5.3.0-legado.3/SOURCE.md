# htmlunit-core-js 5.3.0-legado.3

This binary is built from the following public source revisions:

- Packaging: `skybbk1001/htmlunit-core-js@e31799f290b50f99fe2cef1f14acd9725f69653c`
- Rhino: `skybbk1001/htmlunit-rhino-fork@c758621a0df4ec5687901570dc355c1f98f32652`

`rhinoDiff.txt` records the Rhino changes from upstream merge base
`46d0904a3b4a1adc014a0d53d66a91b699a548de` to the pinned fork revision.

Build command:

```shell
mvn --batch-mode -U clean install -Dmaven.test.skip=true -Dgpg.skip=true -Dmaven.javadoc.skip=true -Dmaven.compiler.showWarnings=false
```

SHA-256 of `htmlunit-core-js-5.3.0-legado.3.jar`:

```text
9684b7d1780b9dbfe39c6f2b5f9e35c44371912e2e508dea4393a5c1ac3e081c
```

Licensing records from both source revisions are bundled in this APK asset
directory and preserved in the source tree:

- The packaging revision declares Apache License 2.0 in its POM, while its
  `LICENSE.txt` states that the generated JavaScript engine is provided under
  MPL 2.0.
- The Rhino revision is MPL 2.0 and includes additional third-party notices.
- `LICENSE-core-js.txt`, `LICENSE.txt`, `LICENSE-APACHE-2.0.txt`, `NOTICE.txt`,
  `NOTICE-tools.txt`, and `rhinoDiff.txt` contain those original records,
  referenced license texts, and source changes. The JAR itself does not embed
  them; Android packaging includes this directory unchanged in the APK assets.

The corresponding source remains available from the exact revisions above.
