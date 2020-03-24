# dysentery documentation

The main resource here is the [Packet
Analysis](https://djl-analysis.deepsymmetry.org/). It is built by
[Antora](https://antora.org) using a [build script](build.sh) on
[Netlify](https://netlify.com), which runs the appropriate
[playbook](netlify.yml) on the [component descriptor](antora.yml) and
[source](modules/ROOT).

## Building Locally

This is thankfully much simpler than it used to be, and can now be
fully driven by `npm`!

1. `cd` into the top level folder of the `dysentery` repository and
   run the following command:

        npm install

    This will install the dependencies needed for building the
    documentation site, including David Jenks' not-yet-merged enhancements
    to Antora.

2. Then whenever you want to build a local preview of the
   documentation site, you can run:

        npm run local-docs

3. To view them in a browser, open the file `doc/build/site/index.html`

## History

For historical interest, the [LaTeX document](Analysis.tex) used to be
where the analysis lived, and it was used to produce a PDF version
hosted online, but some of the underlying packages it depended on
became unmaintained and unreliable, which led to it being ported to
Asciidoctor with the help of a new byte field diagram generator,
[bytefield-svg]
