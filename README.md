# Elasticsearch Legacy Completion Plugin #

This is a "hack" to allow usage of the legacy Elasticsearch 1.x/2.x
completion suggester in Elasticsearch 5.x. This legacy plugin supports
deduplication and is *not* document based.

This fixes the following Elasticsearch issue by a workaround:
https://github.com/elastic/elasticsearch/issues/22912

## Installation and Usage ##

To restore the old behaviour of the old completion suggester for new
indexes created with Elasticsearch 5.x use the following process:

To compile the plugin you need to install 
[maven](https://maven.apache.org/) and a JDK in your environment.

The plugin compiles with Elasticsearch 5.3.0, but should also work with
earlier or later versions. Just edit the POM file, compile, and create
the package:

```sh
git clone https://github.com/uschindler/es-legacy-completion-plugin
cd es-legacy-completion-plugin
mvn clean install
```

After that copy the `es-legacy-completion-plugin-5.X.Y.jar` file and the
`plugin-descriptor.properties` file from the `target` directory to a new
subdirectory (e.g., `legacy-completion`) of Elasticsearch's plugin
folder.

After installataion you can change your ES 2.x mapping to use the new
field type `legacy_completion` instead of `completion` (which
unfortunately triggers new behaviour). When you then query the index
using the usual suggestion endpoint, it will return results in the same
way as Elasticsearch 2.x did.

## How it works ##

Elasticsearch 5.x still has the old suggester code inside to support
Elasticsearch 2.x indexes, but for new indexes it uses the new
suggester. The completion suggester endpoint just checks the Lucene
Codec that was used to create the suggestion field and then uses the old
or new one.

This plugin just adds a new field type that internally enforces the use
of the old Lucene Codec. This way the suggester endpoint automatically
falls back to old behaviour. It does this by some hacks in the field
mapper and (unfortunately), lots of code duplication, but this was kept
minimal.

*Caution:* This is likely to break with Elasticsearch 6!
