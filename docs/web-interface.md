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

## Try an experimental timed capture

Open **HDR Timelapse**, choose **New Session** and select a **Camera Profile**. **Bracket Count** is the number of shots per interval. **EV Step** sets the intended exposure offsets recorded in the shot metadata. The prototype leaves the camera exposure settings as selected on the camera. **Interval (seconds)** and **Total Intervals** control the timing and number of capture intervals. Automatic exposure adjustment, HDR merging and video export are under development.

Choose **Create Session** to review the session and its status. Choose **Start** in the session panel when you are ready to capture. Configuring a session and starting its capture are separate actions. See the [REST API guide](api.md) for programmatic access.

The screenshots were captured in Chromium from the packaged application, using an in-memory database and the project's Java mock camera.
