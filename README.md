# zscan-upload

Jenkins plugin to upload builds to zScan for analysis.

zScan is part of the [Zimperium MAPS](https://www.zimperium.com/mobile-app-protection/) Suite. The suite provides mobile app teams with centralized threat visibility and comprehensive in-app protection from development through runtime.  It combines both inside-out and outside-in security approaches to help organizations build compliant, secure, and resilient mobile apps. Using this plugin to integrate zScan into CI/CD pipelines allows mobile application development teams to detect vulnerabilities earlier in the development lifecycle.

## Pre-requisites

This project requires Java 17 or higher to build. All of the 3rd-party jars we're using are compiled with Java 17.  

A Zimperium Console account with a zScan (MAPS) license is required.  In your console, head over to the *Authorizations* tab in the *Account Management* section and generate a new API key that at least has the permissions of `zScan Builds - Upload`.  If assessment reports are required, the `zScan Assessments - View` permission is also necessary.

## Build

```mvn clean install```

and the artifact `zscan-upload.hpi` will have been created in the `target` directory
(`./target/zscan-upload.hpi`.) Running ```mvn package``` also creates the `.hpi` file.

### Other Maven goals you may find useful when building this plugin

The Maven sub-goals provided by the HPI plugin are documented here:

[Jenkins Maven Plugin Goals](https://jenkinsci.github.io/maven-hpi-plugin/plugin-info.html)

For example, ```mvn hpi:hpi``` builds the `.hpi` file, while ```mvn hpi:run``` starts a test instance of Jenkins with the plugin preloaded.

## Installation

The easiest way to install this plugin is from the Jenkins Marketplace.  If you prefer manual installation, follow these steps:

1. Download one of the official releases from this repo or build one yourself using the above instructions.
2. Manage Jenkins -> Manage Plugins -> Advanced tab -> Deploy Plugin -> Choose file (`zscan-upload.hpi`)
3. Restart Jenkins

## Configuration

### Global Configuration

Settings related to the Zimperium server (console) may be configured globally and then reused by all projects on the server.  These settings can also be overwritten by individual projects.

#### Zimperium Server URL Endpoint

This is going to be your root URL to your console (e.g., `https://ziap.zimperium.com` or `https://zc202.zimperium.com`).

#### Client ID

This is from the `Authorizations` section when you generate your API Key. See [Pre-requisites](#pre-requisites) for details on obtaining credentials.

#### Client Secret

Similar to `Client ID`, this is from the `Authorizations` section when you generate your API Key. See [Pre-requisites](#pre-requisites) for details on obtaining credentials.

#### Use Proxy

When checked, the plugin will use HTTP Proxy settings configured on the `Manage Jenkins | System` page. Otherwise, the plugin will try to connect to the console directly.

### Per-project Configuration

In the `Configure` section of your project, `Add post-build action` and select `Upload build artifacts to zScan`.

To reuse global configuration for the Zimperium Server (console), please leave the `Use Global Console Information` box checked.  If you need to specify a configuration unique to this project (e.g., a different set of credentials), uncheck the box and fill out the details.  Fields that need to be populated are the same as in the [Global Configuration](#global-configuration) section.

Other fields that need to be configured:

#### Source Files

This provides ability to specify patterns to select files to be uploaded, multiple patterns are comma-separated (`*.apk, *.ipa` for example). To prevent accidental flooding of zScan servers, only the first 5 matches will be processed.

#### Excluded Files

Opposite of above, provides ability to specify patterns to exclude files, multiple patterns are comma-separated (`*.md, *.java` for example).

#### Wait for Report

If checked, the plugin will wait for an assessment report after uploading each binary. Reports take about 10 minutes to generate and the build step execution is paused while waiting. Report generation times out after 20 minutes to prevent 'stuck builds'; a timeout marks the build `UNSTABLE` and logs the upload `buildId` and the last status response. Transient status errors (HTTP 404/408/429/5xx) are retried, and repeated unexpected status payloads mark the build `UNSTABLE`. Once an assessment is complete, report downloads are retried for up to 20 minutes while the report becomes available. An assessment that reports `Failed` fails the build step immediately and logs the returned status payload. If unchecked, the execution will move on to the next binary. Reports can also be obtained from the zScan console.

#### Report Format

Specifies the format for the assessment report.  For more information on SARIF, please see [OASIS Open](https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html).

#### Report File Name

Filename(s) for the assessment report(s). Assessment ID is appended to the filename to prevent multiple reports overwriting one another.

### Advanced Configuration

#### Team Name

Team name to assign applications to. If no team name is provided or if a team with the provided name is not found, the 'Default' team is used.  

**Note:** The API key must have additional permissions to be able to manage team assignment: `Common - Teams - Manage` and `zScan Apps - Manage`.  This setting is only relevant when uploading an application for the first time. To change the application's team, please use the zScan Console.

## References

[Jenkins CI Plugin POM on GitHub](https://github.com/jenkinsci/plugin-pom)

[Jenkins CI Plugin Releases](https://github.com/jenkinsci/plugin-pom/releases)

[Choosing the Jenkins baseline](https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/#currently-recommended-versions)

[Jenkins Plugin Development Guide](https://www.jenkins.io/doc/developer/plugin-development/)

[Managing Jenkins Plugins](https://www.jenkins.io/doc/book/managing/plugins/)

[Jenkins Maven Plugin Goals](https://jenkinsci.github.io/maven-hpi-plugin/plugin-info.html)

## License

This plugin is licensed under the MIT License. By using this plugin, you agree to the following terms:

```text
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Enhancements

Submitting improvements to the plugin is welcomed and all pull requests will be approved by Zimperium after review.

## Diagnosing upload/assessment failures

The `scripts/zscan-upload-ab.sh` helper compares the two multipart payload shapes and polls the assessment endpoint to a terminal status. It requires `curl`, `jq`, and these environment variables: `ZSCAN_ENDPOINT`, `ZSCAN_CLIENT_ID`, and `ZSCAN_CLIENT_SECRET`. Do not put credentials directly on the command line.

To compare against the plugin, first run the Jenkins step with the APK and record its logged `buildId`. Then run the minimal request:

```sh
scripts/zscan-upload-ab.sh minimal path/to/app.apk
```

For a direct-request control using the plugin's current CI metadata fields, run:

```sh
scripts/zscan-upload-ab.sh plugin-fields path/to/app.apk
```

The helper prints the `buildId`, each observed scan status, and the final raw status payload. zConsole may deduplicate uploads by APK content, so use separate eligible app builds or otherwise account for console deduplication when comparing results.
