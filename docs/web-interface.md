# Your first capture in Focus Stack

Start the application with the [quick start](quickstart.md). Open its web address and sign in as `admin` using the password configured for that deployment. The **Dashboard** shows configured camera profiles and recent jobs. You can try the capture workflow with the built-in **Mock** backend before connecting a camera.

## Create a camera profile

1. Choose **Add Camera** on the dashboard.
2. Enter a name such as **Mock studio camera**. Select **Mock** under **Backend Type** and choose **Create**.
3. Open **Cameras**. Look for your profile name and its live view. The mock camera generates a colour pattern and reports simulated battery and exposure values. For a physical camera, follow the [camera setup guide](camera-setup.md) and choose the matching backend.

![A mock camera profile with its generated live view, battery level, model, exposure and focus position](screenshots/mock-camera.webp)

## Capture two frames

1. Open **Jobs**, choose **Create Job**, then select your **Camera Profile**.
2. Select the **Fast** preset, change **Max Frames** to **2** and clear **Enable Motion Detection** for this short mock run. The preset also fills in **Step Size**, **Abort Threshold** and **Quality Mode**. Choose the preset before adjusting individual values.
3. Choose **Create**. Open the job by selecting its ID. The job is initially **CREATED**. Choose **Start** in the detail view.
4. Follow the frame count under **Progress**. Use **Refresh** to request the current state. When the job is **COMPLETED**, choose **Export Frames (ZIP)** to download its individual captures. The **Gallery** also lists completed jobs and offers **Download ZIP** and **Merge Stack**.

![New Job form with a mock camera, the Fast preset, a limit of two frames and motion detection switched off](screenshots/new-job.webp)

## Photograph birds at a fixed location

The local bird observer watches the live view and requests a full photograph when it detects a bird. It runs on the application host and needs no cloud API key. Use a configured Canon CCAPI or USB profile whose camera supports both live view and capture. A fixed feeder, nest entrance or perch is a useful first setup: choose exposure and focus for that location before starting.

### Preview and select the observation region

1. Open **Observer** and find **Live bird detection**. Select the **Camera**, then choose **Start preview**.
2. Check **Phase: Observing**, **Mode: Preview only** and a decoded camera image. Preview mode analyses frames without taking photographs. A missing model or camera error appears above the controls.
3. Choose **Draw region** and drag over the feeder or perch. Mouse and touch are supported. Alternatively, enter **Left**, **Top**, **Width** and **Height** as fractions of the full frame, from 0 to 1. Choose **Apply settings** to use the edited region.

The detector receives the selected region's actual pixels. The camera still takes a full photograph with its current framing; the region does not crop the saved original. Keep the likely landing position inside the region and leave enough space for the bird to move.

### Enable automatic capture

Choose **Arm automatic capture** after checking the preview. **Mode: Automatic capture armed** confirms that a qualifying bird can trigger the camera. The model currently identifies generic birds; it does not distinguish an Eisvogel from an Amsel or fill the species ranking.

The initial settings analyse approximately every 500 ms, require a confidence score of 0.5 and a bird area of 0.0025 of the selected region, repeat photographs every five seconds, and reset a visit after three seconds of valid observations without a bird. **Repeat interval (s) = 0** takes one photograph per visit. These are adjustable starting values; the confidence is a model score and the analysis interval is not a measured shutter delay. See [all settings and frame-handling rules](developer/operations.md#local-bird-observer).

### Open a photograph and stop observation

After a capture, choose **View observation** beside **Last capture**, or open an event under **Recent Observations**. The detail page shows the preview and **Download original** retrieves the unchanged JPEG or RAW returned by the camera. A preview labelled **Live-view image at trigger** is the frame used for detection; it is distinct from the captured original.

Choose **Disarm automatic capture** to continue watching without photographs. Choose **Stop observer** to finish and release the camera. A photo already triggered may finish saving while the phase reads **Stopping**. Wait for **Stopped** before starting another camera workflow. Closing the browser leaves an armed observer running; an application restart starts it stopped and unarmed, and the current settings are not yet stored as a reusable location profile.

The built-in mock camera can demonstrate these controls using its generated colour pattern. It contains no bird and does not produce automatic bird-triggered captures. Its unchanged image is correctly reported as a duplicate after the first analysis. Field accuracy, autofocus and timing need a [test with the actual camera](manual-todo.md#local-bird-observer-on-hardware).

## Try an experimental timed capture

Open **HDR Timelapse**, choose **New Session** and select a **Camera Profile**. **Bracket Count** is the number of shots per interval. **EV Step** sets the intended exposure offsets recorded in the shot metadata. The prototype leaves the camera exposure settings as selected on the camera. **Interval (seconds)** and **Total Intervals** control the timing and number of capture intervals. Automatic exposure adjustment, HDR merging and video export are under development.

Choose **Create Session** to review the session and its status. Choose **Start** in the session panel when you are ready to capture. Configuring a session and starting its capture are separate actions. See the [REST API guide](api.md) for programmatic access.

The screenshots were captured in Chromium from the packaged application, using an in-memory database and the project's Java mock camera.
