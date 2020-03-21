# dysentery documentation

The main resource here is the [Packet
Analysis](https://djl-analysis.deepsymmetry.org/). It is built by
[Antora](https://antora.org) using a [build script](build.sh) on
[Netlify](https://netlify.com), which runs the appropriate
[playbook](netlify.yml) on the [component descriptor](antora.yml) and
[source](modules/ROOT).

For historical interest, the [LaTeX document](Analysis.tex) used to be
where the analysis lived, and it was used to produce a PDF version
hosted online, but some of the underlying packages it depended on
became unmaintained and unreliable, which led to it being ported to
Asciidoctor with the help of a new byte field diagram generator,
[bytefield-svg](https://github.com/Deep-Symmetry/bytefield-svg#bytefield-svg).
