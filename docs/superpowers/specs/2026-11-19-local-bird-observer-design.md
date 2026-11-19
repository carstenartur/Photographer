# Local bird observer

## Accepted scope

The user approved implementation of the stationary wildlife design discussed on
19 November 2026: a camera with a long lens aimed at a feeder, nest entrance or
perch; local bird detection before capture; reaction within seconds; species
selection such as kingfisher versus blackbird deferred.

## Behaviour

- Use the existing Photographer camera profiles and Observer dashboard.
- Start with a bundled, versioned pretrained bird detector, executed locally.
- Analyse a normalized rectangular region selected over the full live image.
  Crop decoded pixels before resizing for inference; map detections back to the
  full image. The captured photo retains the camera's original framing/format.
- Defaults: 500 ms analysis interval, confidence 0.5, minimum object area 0.0025
  of the region, 5 s repeat interval, 3 s observed absence to reset a visit.
  A zero repeat interval means one photo per visit. Settings are validated.
- A fresh positive frame can trigger immediately. Motion is not a prerequisite.
- Preview-only is the default. Automatic capture requires an explicit armed
  setting in the user interface. A stop prevents subsequent captures, including
  results from inference already in progress.
- Process at most one frame at a time; never queue old camera frames. Reject old,
  duplicate and malformed frames. Camera outages and capture blackouts do not
  count as observed absence. Do not infer a species from the generic bird label.
- Share existing camera ownership with other workflows; live view and capture
  are serialized. Release live view and ownership after bounded stop/shutdown.
- Persist the captured original and a JPEG preview in the existing observation
  event history. Keep image bytes out of the database and sanitize filenames.
- Show actual running/armed/model state, frame age, inference duration,
  detections, errors and capture counters. The preview and boxes refer to the
  same analysed frame.

## Architecture and alternatives

The chosen starting point is a pretrained ONNX detector on CPU through the
existing Java vision contract. Fine-tuning remains a later improvement based on
real scene errors. A remote image model would introduce a network dependency in
the capture decision; species classification is deferred as the user requested.

`ai-vision` owns the native inference adapter/model. `observer-core` owns the
bird-visit and repeat decision. `app` owns the camera lease, live worker, image
storage, REST composition and existing event persistence. `frontend-web` adds
controls within the existing Observer dashboard. Framework-light modules retain
their existing dependency direction.

## Verification

Use a real, attributed bird photograph for a positive inference regression,
blank/non-bird inputs for negatives and deterministic sequences for trigger,
repeat, absence, error and stop behaviour. Cover ROI geometry and malformed
configuration. Run frontend tests/lint/build and the canonical product gate.
Hardware timing and camera-model compatibility remain measurements requiring a
connected camera; software tests must not be presented as such evidence.
