# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

### Added

- Ability to tell devices to turn sync on or off.
- Ability to appoint a device as tempo master.
- Ability to tell devices they are on or off air when there is no DJM
  on the network.
- Ability to use Fader Start packets to tell devices to start or stop
  playing.
- Ability to tell players to load tracks like rekordbox does.
- Ability to send status updates from the virtual CDJ so it can become
  tempo master and control the tempo.
- A beat logging tool in order to help debug and fine-tune the beat
  sending code in Beat Link.
- Documentation of how to obtain a wide variety of menus.
- Documentation and implementation of database text substring search.
- Documentation of nxs2-style extended cue list requests and
  responses.
- Some preliminary discoveries about the XDJ-XZ.
- Analysis of the BPM Sync bit in the state flag, thanks to
  [David Ng](https://github.com/nudge).
- Explanation of how the mixer assigns device numbers to players
  plugged into channel-specific ports, and all variations of the
  startup process.
- Initial information about new CDJ-3000 fields and packets, thanks to
  [David Ng](https://github.com/nudge).
- Analysis of six-channel on-air packet sent by DJM-V10, thanks to
  [@AhnHEL](https://github.com/AhnHEL).
- Analysis of dynamic loop information reported by CDJ-3000s.

## [0.2.1] - 2018-07-21

### Added

- Ability to retrieve metadata for non-rekordbox tracks.

## [0.2.0] - 2017-06-24

Many thanks to [code shared](https://bitbucket.org/awwright/libpdjl/src)
by Austin Wright, [@awwright](https://github.com/awwright) for enabling
this breakthrough release!

### Added

- Support for 292-byte CDJ status updates sent by newer Pioneer
  firmware.
- Much more robust metadata queries and interpretation.
- Album art retrieval.
- Beat grid retrieval.
- Waveform retrieval.
- Playlist retrieval, and listing all tracks.

## [0.1.5] - 2016-07-04

### Added

- Initial support for nxs2 players, thanks to captures and screen
  shots from @drummerclint and Markus Krooked. Existing fields seem to
  work again, but we have not yet learned how to read any of the new
  features that might be found in the larger status packets.
- Discovered how to find rekordbox track IDs and database location
  information in the CDJ status packets.
- Discovered how to ask a CDJ for track metadata given a database
  location and rekordbox ID.
- Analysis of status packets from rekordbox.

## [0.1.4] - 2016-05-25

### Fixed

- Crash in trying to interpret beat number which blocked opening of
  status windows.

## [0.1.3] - 2016-05-17

### Fixed

- Accept shorter, 208-byte CDJ status packets sent by non-nexus
  players.
- The value of *F* seems to always be zero for non-nexus players.
- The value of *l<sub>1</sub>* can be zero when non-rekordbox tracks
  are loaded.
- The value of *b<sub>b</sub>* is zero when non-rekordbox tracks are
  loaded or the packet is from a non-nexus player.
- The values of *P<sub>2</sub>* are different for non-nexus players.

### Added

- The firmware version number has been found in CDJ status packets.
- Display **n/a** for *Beat* when not available.
- Recognize values for byte 204 that seem to distinguish nexus and
  non-nexus players.

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


[unreleased]: https://github.com/Deep-Symmetry/dysentery/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/Deep-Symmetry/dysentery/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/Deep-Symmetry/dysentery/compare/v0.1.5...v0.2.0
[0.1.5]: https://github.com/Deep-Symmetry/dysentery/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/Deep-Symmetry/dysentery/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/Deep-Symmetry/dysentery/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/Deep-Symmetry/dysentery/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/Deep-Symmetry/dysentery/compare/v0.1.0...v0.1.1
