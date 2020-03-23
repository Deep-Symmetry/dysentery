# dysentery documentation

The main resource here is the [Packet
Analysis](https://djl-analysis.deepsymmetry.org/). It is built by
[Antora](https://antora.org) using a [build script](build.sh) on
[Netlify](https://netlify.com), which runs the appropriate
[playbook](netlify.yml) on the [component descriptor](antora.yml) and
[source](modules/ROOT).

## Building Locally

If you would like to build the documentation site in order to preview
changes you are making, there are some extra steps you need to take
for the time being, because it relies on an as-yet-unreleased fork of
Antora and a similarly-unreleased plugin framework that works with it
to host the
[bytefield-svg](https://github.com/Deep-Symmetry/bytefield-svg#bytefield-svg)
diagram generator. This will get much easier once Antora catches up
and these are released, but for now:

1. Create a directory to host the projects you are going to clone,
   unless you want them in your normal git repository directory. As
   long as they are all in the same directory, this will work.

2. Clone and build [this
   branch](https://gitlab.com/djencks/antora/-/tree/issue-585-with-377-582-git-credential-plugin)
   of the Antora fork that [David Jencks](https://gitlab.com/djencks)
   created, which has the unreleased plugin feature.

       git clone https://gitlab.com/djencks/antora.git
       cd antora
       git checkout issue-585-with-377-582-git-credential-plugin
       yarn
       cd ..

4. Set the environment variable `ANTORA_DJ` to the absolute path of
   the file `packages/cli/bin/antora` which was created in the
   directory in which you cloned and built that Antora branch.

5. After confirming that you are back to the parent directory into
   which you cloned the Antora branch, also clone this branch of
   David's [SVG plugin
   framework](https://gitlab.com/djencks/asciidoctor-generic-svg-extension.js/-/tree/issue-377-plugin).

       git clone https://gitlab.com/djencks/asciidoctor-generic-svg-extension.js.git
       cd asciidoctor-generic-svg-extension.js
       git checkout issue-377-plugin
       cd ..

6. In the same directory, clone
   [dysentery](https://github.com/Deep-Symmetry/dysentery) (or move it
   there if you have already cloned it), and [crate
   digger](https://github.com/Deep-Symmetry/crate-digger), the other
   project which contributes to the documentation site.

       git clone https://github.com/Deep-Symmetry/dysentery.git
       git clone https://github.com/Deep-Symmetry/crate-digger.git

7. `cd` into the `dysentery` repository and run the following two
   commands (you can ignore the warnings about there being no
   `package.json` and therefore no description, repository, README, or
   license fields):

       npm install asciidoctor-mathjax
       npm install bytefield-svg

Assuming everything got installed in the right places and your
`ANTORA_DJ` envronment variable was properly set to point to the
antora fork repository, you can now successfully execute this command
from the root of the `dysentery` repository whenever you want to build
the documentation locally:

       $ANTORA_DJ --fetch doc/local.yml

Running that will result in building the documentation site in the
`doc/build` subdirectory, based on the current source in your
repository. You can view it by telling a browser to open
`doc/build/site/index.html`.

## History

For historical interest, the [LaTeX document](Analysis.tex) used to be
where the analysis lived, and it was used to produce a PDF version
hosted online, but some of the underlying packages it depended on
became unmaintained and unreliable, which led to it being ported to
Asciidoctor with the help of a new byte field diagram generator,
[bytefield-svg]
