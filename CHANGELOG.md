# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

Nothing so far.

## [0.1.2] - 2016-05-12

### Fixed

- A hang when AoT-compiling the project to build the uberjar caused by
  starting a future at compile time.

### Added

- The mixer's status flag has been located.
- Closing windows only shuts down the application when it was run as
  an executable jar.
- Notes about the rekordbox database format.
- A capture of and discussion of Link Info TCP communication between
  CDJs.

## [0.1.1] - 2016-05-01

### Added

- A detailed article that will be the primary repository of knowledge
  for the project of analyzing the protocol.
- Values in the packet windows are color coded to indicate when they
  have an expected value, to help identify flaws in our analysis.
- Bytes which are expected to contain text in the packet windows are
  rendered as text rather than hex, to aid in reading the packet.
- A section at the bottom of the packet capture windows now displays
  the known interpretations of packet fields.
- A packet timestamp.
- Many more bytes in the CDJ status packets have known meanings.
- More bytes in the mixer status packet have known meanings.
- Windows analyzing packets broadcast to port 50001.

## 0.1.0 - 2016-04-26

### Added

- Intial early release.


[unreleased]: https://github.com/brunchboy/dysentery/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/brunchboy/dysentery/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/brunchboy/dysentery/compare/v0.1.0...v0.1.1

