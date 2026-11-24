// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// VIE City VR: OpenXR-Schicht für die Quest.
// Zeigt den Remote-Play-Stream auf einer gebogenen Leinwand und liefert
// Kopf, Hände und Tasten der Touch-Controller an die Kotlin-Seite.
// Das Video landet ohne Umweg in einer Android-Surface-Swapchain,
// der Decoder schreibt also direkt in die Leinwand.

#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <thread>
#include <vector>

#define LOG_TAG "VieCityXR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Aufbau des Felds, das je Bild an Kotlin geht. Muss zu XrFrame.kt passen.
enum {
	F_HEAD_QUAT = 0,   // x y z w
	F_HEAD_POS = 4,    // x y z
	F_RIGHT_QUAT = 7,
	F_RIGHT_POS = 11,
	F_LEFT_QUAT = 14,
	F_LEFT_POS = 18,
	F_LX = 21, F_LY, F_RX, F_RY,
	F_LTRIG, F_RTRIG, F_LGRIP, F_RGRIP,
	F_BUTTONS,
	F_VALID,
	F_COUNT
};

enum {
	BTN_A = 1, BTN_B = 2, BTN_X = 4, BTN_Y = 8,
	BTN_LSTICK = 16, BTN_RSTICK = 32, BTN_MENU = 64
};

enum { VALID_HEAD = 1, VALID_RIGHT = 2, VALID_LEFT = 4 };

const int kVideoWidth = 1920;
const int kVideoHeight = 1080;

struct Screen {
	std::atomic<float> radius{2.0f};       // Meter
	std::atomic<float> angle{2.2f};        // Bogen in Radiant, etwa 126 Grad
	std::atomic<float> height{0.0f};       // Höhe der Mitte gegenüber dem Kopf
	// Leinwand folgt dem Kopf. Mit Kopf-Kamera bleibt das Bild so vor den Augen,
	// statt dass man aus der Leinwand herausschaut.
	std::atomic<bool> follow{true};
	std::atomic<float> followTau{0.18f};   // Sekunden, kleiner = straffer
	// Helligkeit der Umgebung rund um die Leinwand, 0 = schwarz
	std::atomic<float> ambient{0.0f};
	// Lage im LOCAL-Raum, nur vom XR-Thread benutzt
	float yaw = 0.0f, pitch = 0.0f;
	float pos[3] = {0.0f, 0.0f, 0.0f};
	XrTime lastTime = 0;
};

struct Haptic {
	std::atomic<float> amplitude[2] = {{0.0f}, {0.0f}};
	std::atomic<int> durationMs[2] = {{0}, {0}};
	std::atomic<bool> pending[2] = {{false}, {false}};
};

struct App {
	JavaVM *vm = nullptr;
	jobject activity = nullptr;   // globale Referenz
	jmethodID onFrame = nullptr;
	jmethodID onSurface = nullptr;
	jmethodID onExit = nullptr;

	std::thread thread;
	std::atomic<bool> stopRequested{false};
	std::atomic<bool> recenterRequested{false};
	Screen screen;
	Haptic haptic;

	EGLDisplay eglDisplay = EGL_NO_DISPLAY;
	EGLConfig eglConfig = nullptr;
	EGLContext eglContext = EGL_NO_CONTEXT;
	EGLSurface eglSurface = EGL_NO_SURFACE;

	XrInstance instance = XR_NULL_HANDLE;
	XrSystemId systemId = XR_NULL_SYSTEM_ID;
	XrSession session = XR_NULL_HANDLE;
	XrSpace localSpace = XR_NULL_HANDLE;
	XrSpace viewSpace = XR_NULL_HANDLE;
	XrSwapchain videoSwapchain = XR_NULL_HANDLE;
	jobject videoSurface = nullptr;
	bool sessionRunning = false;
	bool hasImageLayout = false, hasLayerSettings = false, hasColorScale = false, hasEquirect = false;

	// Umgebung: ein Rundumbild (2:1), einmal hochgeladen und danach nur noch gezeigt
	std::vector<uint8_t> envPixels;
	int envWidth = 0, envHeight = 0;
	XrSwapchain envSwapchain = XR_NULL_HANDLE;

	// Hilfe-Tafel mit der Tastenbelegung, erscheint unter der Leinwand
	std::vector<uint8_t> hintPixels;
	int hintWidth = 0, hintHeight = 0;
	XrSwapchain hintSwapchain = XR_NULL_HANDLE;
	std::atomic<bool> hintVisible{false};

	XrActionSet actionSet = XR_NULL_HANDLE;
	XrPath handPath[2] = {XR_NULL_PATH, XR_NULL_PATH}; // 0 links, 1 rechts
	XrAction stick = XR_NULL_HANDLE, trigger = XR_NULL_HANDLE, grip = XR_NULL_HANDLE;
	XrAction stickClick = XR_NULL_HANDLE, aimPose = XR_NULL_HANDLE, vibrate = XR_NULL_HANDLE;
	XrAction btnA = XR_NULL_HANDLE, btnB = XR_NULL_HANDLE, btnX = XR_NULL_HANDLE, btnY = XR_NULL_HANDLE;
	XrAction btnMenu = XR_NULL_HANDLE;
	XrSpace aimSpace[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
};

App *g_app = nullptr;

bool check(XrResult r, const char *what)
{
	if(XR_FAILED(r))
	{
		LOGE("%s fehlgeschlagen: %d", what, (int)r);
		return false;
	}
	return true;
}

bool initEgl(App &app)
{
	app.eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if(app.eglDisplay == EGL_NO_DISPLAY || !eglInitialize(app.eglDisplay, nullptr, nullptr))
		return false;
	const EGLint configAttribs[] = {
		EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
		EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
		EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
		EGL_NONE
	};
	EGLint count = 0;
	if(!eglChooseConfig(app.eglDisplay, configAttribs, &app.eglConfig, 1, &count) || count < 1)
		return false;
	const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
	app.eglContext = eglCreateContext(app.eglDisplay, app.eglConfig, EGL_NO_CONTEXT, contextAttribs);
	if(app.eglContext == EGL_NO_CONTEXT)
		return false;
	const EGLint surfaceAttribs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
	app.eglSurface = eglCreatePbufferSurface(app.eglDisplay, app.eglConfig, surfaceAttribs);
	if(app.eglSurface == EGL_NO_SURFACE)
		return false;
	return eglMakeCurrent(app.eglDisplay, app.eglSurface, app.eglSurface, app.eglContext) == EGL_TRUE;
}

void shutdownEgl(App &app)
{
	if(app.eglDisplay == EGL_NO_DISPLAY)
		return;
	eglMakeCurrent(app.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
	if(app.eglSurface != EGL_NO_SURFACE)
		eglDestroySurface(app.eglDisplay, app.eglSurface);
	if(app.eglContext != EGL_NO_CONTEXT)
		eglDestroyContext(app.eglDisplay, app.eglContext);
	eglTerminate(app.eglDisplay);
	app.eglDisplay = EGL_NO_DISPLAY;
}

XrPath path(App &app, const char *s)
{
	XrPath p = XR_NULL_PATH;
	xrStringToPath(app.instance, s, &p);
	return p;
}

XrAction makeAction(App &app, XrActionType type, const char *name, bool bothHands)
{
	XrActionCreateInfo info = {XR_TYPE_ACTION_CREATE_INFO};
	info.actionType = type;
	strncpy(info.actionName, name, XR_MAX_ACTION_NAME_SIZE - 1);
	strncpy(info.localizedActionName, name, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
	if(bothHands)
	{
		info.countSubactionPaths = 2;
		info.subactionPaths = app.handPath;
	}
	XrAction action = XR_NULL_HANDLE;
	check(xrCreateAction(app.actionSet, &info, &action), name);
	return action;
}

bool initActions(App &app)
{
	app.handPath[0] = path(app, "/user/hand/left");
	app.handPath[1] = path(app, "/user/hand/right");

	XrActionSetCreateInfo setInfo = {XR_TYPE_ACTION_SET_CREATE_INFO};
	strcpy(setInfo.actionSetName, "play");
	strcpy(setInfo.localizedActionSetName, "Play");
	if(!check(xrCreateActionSet(app.instance, &setInfo, &app.actionSet), "xrCreateActionSet"))
		return false;

	app.stick = makeAction(app, XR_ACTION_TYPE_VECTOR2F_INPUT, "stick", true);
	app.trigger = makeAction(app, XR_ACTION_TYPE_FLOAT_INPUT, "trigger", true);
	app.grip = makeAction(app, XR_ACTION_TYPE_FLOAT_INPUT, "grip", true);
	app.stickClick = makeAction(app, XR_ACTION_TYPE_BOOLEAN_INPUT, "stick_click", true);
	app.aimPose = makeAction(app, XR_ACTION_TYPE_POSE_INPUT, "aim", true);
	app.vibrate = makeAction(app, XR_ACTION_TYPE_VIBRATION_OUTPUT, "vibrate", true);
	app.btnA = makeAction(app, XR_ACTION_TYPE_BOOLEAN_INPUT, "a", false);
	app.btnB = makeAction(app, XR_ACTION_TYPE_BOOLEAN_INPUT, "b", false);
	app.btnX = makeAction(app, XR_ACTION_TYPE_BOOLEAN_INPUT, "x", false);
	app.btnY = makeAction(app, XR_ACTION_TYPE_BOOLEAN_INPUT, "y", false);
	app.btnMenu = makeAction(app, XR_ACTION_TYPE_BOOLEAN_INPUT, "menu", false);

	std::vector<XrActionSuggestedBinding> b = {
		{app.stick, path(app, "/user/hand/left/input/thumbstick")},
		{app.stick, path(app, "/user/hand/right/input/thumbstick")},
		{app.trigger, path(app, "/user/hand/left/input/trigger/value")},
		{app.trigger, path(app, "/user/hand/right/input/trigger/value")},
		{app.grip, path(app, "/user/hand/left/input/squeeze/value")},
		{app.grip, path(app, "/user/hand/right/input/squeeze/value")},
		{app.stickClick, path(app, "/user/hand/left/input/thumbstick/click")},
		{app.stickClick, path(app, "/user/hand/right/input/thumbstick/click")},
		{app.aimPose, path(app, "/user/hand/left/input/aim/pose")},
		{app.aimPose, path(app, "/user/hand/right/input/aim/pose")},
		{app.vibrate, path(app, "/user/hand/left/output/haptic")},
		{app.vibrate, path(app, "/user/hand/right/output/haptic")},
		{app.btnA, path(app, "/user/hand/right/input/a/click")},
		{app.btnB, path(app, "/user/hand/right/input/b/click")},
		{app.btnX, path(app, "/user/hand/left/input/x/click")},
		{app.btnY, path(app, "/user/hand/left/input/y/click")},
		{app.btnMenu, path(app, "/user/hand/left/input/menu/click")},
	};
	XrInteractionProfileSuggestedBinding suggested = {XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
	suggested.interactionProfile = path(app, "/interaction_profiles/oculus/touch_controller");
	suggested.suggestedBindings = b.data();
	suggested.countSuggestedBindings = (uint32_t)b.size();
	if(!check(xrSuggestInteractionProfileBindings(app.instance, &suggested), "xrSuggestInteractionProfileBindings"))
		return false;

	for(int hand = 0; hand < 2; hand++)
	{
		XrActionSpaceCreateInfo spaceInfo = {XR_TYPE_ACTION_SPACE_CREATE_INFO};
		spaceInfo.action = app.aimPose;
		spaceInfo.subactionPath = app.handPath[hand];
		spaceInfo.poseInActionSpace.orientation.w = 1.0f;
		check(xrCreateActionSpace(app.session, &spaceInfo, &app.aimSpace[hand]), "xrCreateActionSpace");
	}

	XrSessionActionSetsAttachInfo attach = {XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
	attach.countActionSets = 1;
	attach.actionSets = &app.actionSet;
	return check(xrAttachSessionActionSets(app.session, &attach), "xrAttachSessionActionSets");
}

// Lädt ein festes Bild (RGBA, oberste Zeile zuerst) einmal in eine eigene Swapchain.
XrSwapchain createStaticImage(App &app, const std::vector<uint8_t> &pixels, int w, int h)
{
	if(pixels.empty() || w <= 0 || h <= 0)
		return XR_NULL_HANDLE;
	uint32_t formatCount = 0;
	xrEnumerateSwapchainFormats(app.session, 0, &formatCount, nullptr);
	std::vector<int64_t> formats(formatCount);
	xrEnumerateSwapchainFormats(app.session, formatCount, &formatCount, formats.data());
	int64_t format = 0;
	for(int64_t wanted : {(int64_t)GL_SRGB8_ALPHA8, (int64_t)GL_RGBA8})
	{
		for(int64_t f : formats)
			if(f == wanted && !format)
				format = f;
	}
	if(!format)
		return XR_NULL_HANDLE;

	XrSwapchainCreateInfo info = {XR_TYPE_SWAPCHAIN_CREATE_INFO};
	info.createFlags = XR_SWAPCHAIN_CREATE_STATIC_IMAGE_BIT;
	info.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
	info.format = format;
	info.sampleCount = 1;
	info.width = (uint32_t)w;
	info.height = (uint32_t)h;
	info.faceCount = 1;
	info.arraySize = 1;
	info.mipCount = 1;
	XrSwapchain swapchain = XR_NULL_HANDLE;
	if(!check(xrCreateSwapchain(app.session, &info, &swapchain), "Bild: xrCreateSwapchain"))
		return XR_NULL_HANDLE;

	uint32_t imageCount = 0;
	xrEnumerateSwapchainImages(swapchain, 0, &imageCount, nullptr);
	std::vector<XrSwapchainImageOpenGLESKHR> images(imageCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
	xrEnumerateSwapchainImages(swapchain, imageCount, &imageCount, (XrSwapchainImageBaseHeader *)images.data());

	uint32_t index = 0;
	XrSwapchainImageWaitInfo wait = {XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
	wait.timeout = XR_INFINITE_DURATION;
	if(imageCount < 1 || XR_FAILED(xrAcquireSwapchainImage(swapchain, nullptr, &index))
		|| XR_FAILED(xrWaitSwapchainImage(swapchain, &wait)))
	{
		xrDestroySwapchain(swapchain);
		return XR_NULL_HANDLE;
	}
	// Die Textur erwartet die unterste Zeile zuerst.
	std::vector<uint8_t> flipped(pixels.size());
	for(int y = 0; y < h; y++)
		memcpy(&flipped[(size_t)y * w * 4], &pixels[(size_t)(h - 1 - y) * w * 4], (size_t)w * 4);
	glBindTexture(GL_TEXTURE_2D, images[index].image);
	glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
	glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, flipped.data());
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glBindTexture(GL_TEXTURE_2D, 0);
	glFinish();
	xrReleaseSwapchainImage(swapchain, nullptr);
	return swapchain;
}

void initImages(App &app)
{
	if(app.hasEquirect)
		app.envSwapchain = createStaticImage(app, app.envPixels, app.envWidth, app.envHeight);
	app.hintSwapchain = createStaticImage(app, app.hintPixels, app.hintWidth, app.hintHeight);
	app.envPixels.clear();
	app.envPixels.shrink_to_fit();
	app.hintPixels.clear();
	app.hintPixels.shrink_to_fit();
	LOGI("Bilder geladen: Umgebung %d, Hilfe %d", app.envSwapchain != XR_NULL_HANDLE, app.hintSwapchain != XR_NULL_HANDLE);
}

bool initXr(App &app, JNIEnv *env)
{
	XrLoaderInitInfoAndroidKHR loaderInfo = {XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
	loaderInfo.applicationVM = app.vm;
	loaderInfo.applicationContext = app.activity;
	PFN_xrInitializeLoaderKHR initLoader = nullptr;
	xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction *)&initLoader);
	if(!initLoader || !check(initLoader((XrLoaderInitInfoBaseHeaderKHR *)&loaderInfo), "xrInitializeLoaderKHR"))
		return false;

	std::vector<const char *> extensions = {
		XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
		XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
		XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME,
		XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME,
	};
	// Zusätze nur einschalten, wenn das Headset sie kennt, sonst scheitert der Start.
	{
		uint32_t count = 0;
		xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr);
		std::vector<XrExtensionProperties> available(count, {XR_TYPE_EXTENSION_PROPERTIES});
		xrEnumerateInstanceExtensionProperties(nullptr, count, &count, available.data());
		auto has = [&](const char *name) {
			for(auto &e : available)
				if(!strcmp(e.extensionName, name))
					return true;
			return false;
		};
		if((app.hasImageLayout = has(XR_FB_COMPOSITION_LAYER_IMAGE_LAYOUT_EXTENSION_NAME)))
			extensions.push_back(XR_FB_COMPOSITION_LAYER_IMAGE_LAYOUT_EXTENSION_NAME);
		// Nachschärfen (XR_FB_composition_layer_settings) bleibt aus: Auf der Quest 3 lag damit
		// ein heller Kreis über dem Bild, und Bildschirmfotos wurden schwarz.
		app.hasLayerSettings = false;
		if((app.hasColorScale = has(XR_KHR_COMPOSITION_LAYER_COLOR_SCALE_BIAS_EXTENSION_NAME)))
			extensions.push_back(XR_KHR_COMPOSITION_LAYER_COLOR_SCALE_BIAS_EXTENSION_NAME);
		if((app.hasEquirect = has(XR_KHR_COMPOSITION_LAYER_EQUIRECT2_EXTENSION_NAME)))
			extensions.push_back(XR_KHR_COMPOSITION_LAYER_EQUIRECT2_EXTENSION_NAME);
		LOGI("Zusätze: Bildlage %d, Schärfen %d, Dimmen %d", app.hasImageLayout, app.hasLayerSettings, app.hasColorScale);
	}
	XrInstanceCreateInfoAndroidKHR androidInfo = {XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
	androidInfo.applicationVM = app.vm;
	androidInfo.applicationActivity = app.activity;
	XrInstanceCreateInfo instanceInfo = {XR_TYPE_INSTANCE_CREATE_INFO};
	instanceInfo.next = &androidInfo;
	strcpy(instanceInfo.applicationInfo.applicationName, "VIE City VR");
	instanceInfo.applicationInfo.applicationVersion = 1;
	strcpy(instanceInfo.applicationInfo.engineName, "viecity");
	instanceInfo.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 34);
	instanceInfo.enabledExtensionCount = (uint32_t)extensions.size();
	instanceInfo.enabledExtensionNames = extensions.data();
	if(!check(xrCreateInstance(&instanceInfo, &app.instance), "xrCreateInstance"))
		return false;

	XrSystemGetInfo systemInfo = {XR_TYPE_SYSTEM_GET_INFO};
	systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
	if(!check(xrGetSystem(app.instance, &systemInfo, &app.systemId), "xrGetSystem"))
		return false;

	PFN_xrGetOpenGLESGraphicsRequirementsKHR getRequirements = nullptr;
	xrGetInstanceProcAddr(app.instance, "xrGetOpenGLESGraphicsRequirementsKHR", (PFN_xrVoidFunction *)&getRequirements);
	XrGraphicsRequirementsOpenGLESKHR requirements = {XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
	if(!getRequirements || !check(getRequirements(app.instance, app.systemId, &requirements), "GraphicsRequirements"))
		return false;

	XrGraphicsBindingOpenGLESAndroidKHR binding = {XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
	binding.display = app.eglDisplay;
	binding.config = app.eglConfig;
	binding.context = app.eglContext;
	XrSessionCreateInfo sessionInfo = {XR_TYPE_SESSION_CREATE_INFO};
	sessionInfo.next = &binding;
	sessionInfo.systemId = app.systemId;
	if(!check(xrCreateSession(app.instance, &sessionInfo, &app.session), "xrCreateSession"))
		return false;

	XrReferenceSpaceCreateInfo spaceInfo = {XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
	spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
	spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
	if(!check(xrCreateReferenceSpace(app.session, &spaceInfo, &app.localSpace), "LOCAL"))
		return false;
	spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
	if(!check(xrCreateReferenceSpace(app.session, &spaceInfo, &app.viewSpace), "VIEW"))
		return false;

	PFN_xrCreateSwapchainAndroidSurfaceKHR createSurfaceSwapchain = nullptr;
	xrGetInstanceProcAddr(app.instance, "xrCreateSwapchainAndroidSurfaceKHR", (PFN_xrVoidFunction *)&createSurfaceSwapchain);
	XrSwapchainCreateInfo swapchainInfo = {XR_TYPE_SWAPCHAIN_CREATE_INFO};
	swapchainInfo.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
	swapchainInfo.width = kVideoWidth;
	swapchainInfo.height = kVideoHeight;
	jobject surface = nullptr;
	if(!createSurfaceSwapchain || !check(createSurfaceSwapchain(app.session, &swapchainInfo, &app.videoSwapchain, &surface), "xrCreateSwapchainAndroidSurfaceKHR"))
		return false;
	app.videoSurface = env->NewGlobalRef(surface);

	initImages(app);
	return initActions(app);
}

float readFloat(App &app, XrAction action, int hand)
{
	XrActionStateGetInfo info = {XR_TYPE_ACTION_STATE_GET_INFO};
	info.action = action;
	info.subactionPath = app.handPath[hand];
	XrActionStateFloat state = {XR_TYPE_ACTION_STATE_FLOAT};
	xrGetActionStateFloat(app.session, &info, &state);
	return state.isActive ? state.currentState : 0.0f;
}

bool readBool(App &app, XrAction action, XrPath subaction)
{
	XrActionStateGetInfo info = {XR_TYPE_ACTION_STATE_GET_INFO};
	info.action = action;
	info.subactionPath = subaction;
	XrActionStateBoolean state = {XR_TYPE_ACTION_STATE_BOOLEAN};
	xrGetActionStateBoolean(app.session, &info, &state);
	return state.isActive && state.currentState;
}

bool locate(XrSpace space, XrSpace base, XrTime time, float *quat, float *pos)
{
	XrSpaceLocation loc = {XR_TYPE_SPACE_LOCATION};
	if(XR_FAILED(xrLocateSpace(space, base, time, &loc)))
		return false;
	if(!(loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT))
		return false;
	quat[0] = loc.pose.orientation.x;
	quat[1] = loc.pose.orientation.y;
	quat[2] = loc.pose.orientation.z;
	quat[3] = loc.pose.orientation.w;
	pos[0] = loc.pose.position.x;
	pos[1] = loc.pose.position.y;
	pos[2] = loc.pose.position.z;
	return true;
}

void readInput(App &app, XrTime time, float *f)
{
	XrActiveActionSet active = {app.actionSet, XR_NULL_PATH};
	XrActionsSyncInfo sync = {XR_TYPE_ACTIONS_SYNC_INFO};
	sync.countActiveActionSets = 1;
	sync.activeActionSets = &active;
	xrSyncActions(app.session, &sync);

	int valid = 0;
	if(locate(app.viewSpace, app.localSpace, time, f + F_HEAD_QUAT, f + F_HEAD_POS))
		valid |= VALID_HEAD;
	if(locate(app.aimSpace[1], app.localSpace, time, f + F_RIGHT_QUAT, f + F_RIGHT_POS))
		valid |= VALID_RIGHT;
	if(locate(app.aimSpace[0], app.localSpace, time, f + F_LEFT_QUAT, f + F_LEFT_POS))
		valid |= VALID_LEFT;
	f[F_VALID] = (float)valid;

	for(int hand = 0; hand < 2; hand++)
	{
		XrActionStateGetInfo info = {XR_TYPE_ACTION_STATE_GET_INFO};
		info.action = app.stick;
		info.subactionPath = app.handPath[hand];
		XrActionStateVector2f state = {XR_TYPE_ACTION_STATE_VECTOR2F};
		xrGetActionStateVector2f(app.session, &info, &state);
		f[hand == 0 ? F_LX : F_RX] = state.isActive ? state.currentState.x : 0.0f;
		f[hand == 0 ? F_LY : F_RY] = state.isActive ? state.currentState.y : 0.0f;
	}
	f[F_LTRIG] = readFloat(app, app.trigger, 0);
	f[F_RTRIG] = readFloat(app, app.trigger, 1);
	f[F_LGRIP] = readFloat(app, app.grip, 0);
	f[F_RGRIP] = readFloat(app, app.grip, 1);

	int buttons = 0;
	if(readBool(app, app.btnA, XR_NULL_PATH)) buttons |= BTN_A;
	if(readBool(app, app.btnB, XR_NULL_PATH)) buttons |= BTN_B;
	if(readBool(app, app.btnX, XR_NULL_PATH)) buttons |= BTN_X;
	if(readBool(app, app.btnY, XR_NULL_PATH)) buttons |= BTN_Y;
	if(readBool(app, app.btnMenu, XR_NULL_PATH)) buttons |= BTN_MENU;
	if(readBool(app, app.stickClick, app.handPath[0])) buttons |= BTN_LSTICK;
	if(readBool(app, app.stickClick, app.handPath[1])) buttons |= BTN_RSTICK;
	f[F_BUTTONS] = (float)buttons;
}

void applyHaptics(App &app)
{
	for(int hand = 0; hand < 2; hand++)
	{
		if(!app.haptic.pending[hand].exchange(false))
			continue;
		XrHapticActionInfo info = {XR_TYPE_HAPTIC_ACTION_INFO};
		info.action = app.vibrate;
		info.subactionPath = app.handPath[hand];
		float amplitude = app.haptic.amplitude[hand].load();
		if(amplitude <= 0.0f)
		{
			xrStopHapticFeedback(app.session, &info);
			continue;
		}
		XrHapticVibration vibration = {XR_TYPE_HAPTIC_VIBRATION};
		vibration.amplitude = amplitude > 1.0f ? 1.0f : amplitude;
		vibration.duration = (XrDuration)app.haptic.durationMs[hand].load() * 1000000LL;
		vibration.frequency = XR_FREQUENCY_UNSPECIFIED;
		xrApplyHapticFeedback(app.session, &info, (const XrHapticBaseHeader *)&vibration);
	}
}

void headAngles(const float *q, float &yaw, float &pitch)
{
	float x = q[0], y = q[1], z = q[2], w = q[3];
	// Blickrichtung = q * (0, 0, -1)
	float fx = -(2.0f * (x * z + w * y));
	float fy = -(2.0f * (y * z - w * x));
	float fz = -(1.0f - 2.0f * (x * x + y * y));
	yaw = atan2f(-fx, -fz);
	pitch = asinf(fy > 1.0f ? 1.0f : (fy < -1.0f ? -1.0f : fy));
}

// Führt die Leinwand: beim Ausrichten sofort vor den Kopf, im Folge-Modus weich hinterher.
void updateScreen(App &app, const float *headQuat, const float *headPos, XrTime time, bool snap)
{
	Screen &sc = app.screen;
	float yaw, pitch;
	headAngles(headQuat, yaw, pitch);
	float dt = sc.lastTime ? (float)((double)(time - sc.lastTime) / 1e9) : 0.0f;
	sc.lastTime = time;

	if(snap)
	{
		sc.yaw = yaw;
		sc.pitch = sc.follow ? pitch : 0.0f;
		memcpy(sc.pos, headPos, sizeof(sc.pos));
		return;
	}
	if(!sc.follow)
		return;

	float tau = sc.followTau.load();
	float k = (tau <= 0.001f || dt <= 0.0f) ? 1.0f : 1.0f - expf(-dt / tau);
	float dYaw = yaw - sc.yaw;
	while(dYaw > (float)M_PI) dYaw -= 2.0f * (float)M_PI;
	while(dYaw < -(float)M_PI) dYaw += 2.0f * (float)M_PI;
	sc.yaw += dYaw * k;
	sc.pitch += (pitch - sc.pitch) * k;
	for(int i = 0; i < 3; i++)
		sc.pos[i] += (headPos[i] - sc.pos[i]) * k;
}

void handleEvents(App &app, bool &exitLoop)
{
	XrEventDataBuffer event = {XR_TYPE_EVENT_DATA_BUFFER};
	while(xrPollEvent(app.instance, &event) == XR_SUCCESS)
	{
		if(event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED)
		{
			auto *changed = (XrEventDataSessionStateChanged *)&event;
			switch(changed->state)
			{
				case XR_SESSION_STATE_READY:
				{
					XrSessionBeginInfo begin = {XR_TYPE_SESSION_BEGIN_INFO};
					begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
					app.sessionRunning = check(xrBeginSession(app.session, &begin), "xrBeginSession");
					app.recenterRequested = true;
					break;
				}
				case XR_SESSION_STATE_STOPPING:
					xrEndSession(app.session);
					app.sessionRunning = false;
					break;
				case XR_SESSION_STATE_EXITING:
				case XR_SESSION_STATE_LOSS_PENDING:
					exitLoop = true;
					break;
				default:
					break;
			}
		}
		else if(event.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING)
			exitLoop = true;
		event = {XR_TYPE_EVENT_DATA_BUFFER};
	}
}

void threadMain(App *appPtr)
{
	App &app = *appPtr;
	JNIEnv *env = nullptr;
	app.vm->AttachCurrentThread(&env, nullptr);

	bool ok = initEgl(app) && initXr(app, env);
	if(ok)
		env->CallVoidMethod(app.activity, app.onSurface, app.videoSurface);
	else
		LOGE("Start der XR-Schicht fehlgeschlagen");

	jfloatArray frameArray = (jfloatArray)env->NewGlobalRef(env->NewFloatArray(F_COUNT));
	float f[F_COUNT] = {};
	bool exitLoop = !ok;
	bool exitAsked = false;

	while(!exitLoop)
	{
		handleEvents(app, exitLoop);
		if(app.stopRequested && !exitAsked)
		{
			exitAsked = true;
			if(app.sessionRunning)
				xrRequestExitSession(app.session);
			else
				break;
		}
		if(!app.sessionRunning)
		{
			std::this_thread::sleep_for(std::chrono::milliseconds(20));
			continue;
		}

		XrFrameState frameState = {XR_TYPE_FRAME_STATE};
		if(XR_FAILED(xrWaitFrame(app.session, nullptr, &frameState)))
			break;
		xrBeginFrame(app.session, nullptr);

		readInput(app, frameState.predictedDisplayTime, f);
		applyHaptics(app);
		if((int)f[F_VALID] & VALID_HEAD)
			updateScreen(app, f + F_HEAD_QUAT, f + F_HEAD_POS, frameState.predictedDisplayTime, app.recenterRequested.exchange(false));
		env->SetFloatArrayRegion(frameArray, 0, F_COUNT, f);
		env->CallVoidMethod(app.activity, app.onFrame, frameArray);
		if(env->ExceptionCheck())
		{
			env->ExceptionDescribe();
			env->ExceptionClear();
		}

		// Bilder aus einer Android-Surface stehen auf der Quest sonst auf dem Kopf.
		XrCompositionLayerImageLayoutFB layout = {XR_TYPE_COMPOSITION_LAYER_IMAGE_LAYOUT_FB};
		layout.flags = XR_COMPOSITION_LAYER_IMAGE_LAYOUT_VERTICAL_FLIP_BIT_FB;
		// Die Leinwand wird vom System nachgeschärft, das hilft dem gestreckten Videobild.
		XrCompositionLayerSettingsFB sharpen = {XR_TYPE_COMPOSITION_LAYER_SETTINGS_FB};
		sharpen.layerFlags = XR_COMPOSITION_LAYER_SETTINGS_QUALITY_SHARPENING_BIT_FB;
		void *chain = nullptr;
		if(app.hasImageLayout) { layout.next = chain; chain = &layout; }
		if(app.hasLayerSettings) { sharpen.next = chain; chain = &sharpen; }
		XrCompositionLayerCylinderKHR cylinder = {XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
		cylinder.next = chain;
		cylinder.space = app.localSpace;
		cylinder.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
		cylinder.subImage.swapchain = app.videoSwapchain;
		cylinder.subImage.imageRect.extent.width = kVideoWidth;
		cylinder.subImage.imageRect.extent.height = kVideoHeight;
		{
			// Erst um die Hochachse drehen, dann neigen.
			float sy = sinf(app.screen.yaw * 0.5f), cy = cosf(app.screen.yaw * 0.5f);
			float sx = sinf(app.screen.pitch * 0.5f), cx = cosf(app.screen.pitch * 0.5f);
			cylinder.pose.orientation.x = cy * sx;
			cylinder.pose.orientation.y = sy * cx;
			cylinder.pose.orientation.z = -sy * sx;
			cylinder.pose.orientation.w = cy * cx;
		}
		cylinder.pose.position.x = app.screen.pos[0];
		cylinder.pose.position.y = app.screen.pos[1] + app.screen.height.load();
		cylinder.pose.position.z = app.screen.pos[2];
		cylinder.radius = app.screen.radius.load();
		cylinder.centralAngle = app.screen.angle.load();
		cylinder.aspectRatio = (float)kVideoWidth / (float)kVideoHeight;

		// Umgebung: Rundumbild hinter der Leinwand, fest im Raum.
		XrCompositionLayerColorScaleBiasKHR dim = {XR_TYPE_COMPOSITION_LAYER_COLOR_SCALE_BIAS_KHR};
		float ambient = app.screen.ambient.load();
		dim.colorScale = {ambient, ambient, ambient, 1.0f};
		XrCompositionLayerEquirect2KHR surround = {XR_TYPE_COMPOSITION_LAYER_EQUIRECT2_KHR};
		surround.next = app.hasColorScale ? &dim : nullptr;
		surround.space = app.localSpace;
		surround.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
		surround.subImage.swapchain = app.envSwapchain;
		surround.subImage.imageRect.extent.width = app.envWidth;
		surround.subImage.imageRect.extent.height = app.envHeight;
		surround.pose.orientation.w = 1.0f;
		surround.radius = 0.0f;
		surround.centralHorizontalAngle = 2.0f * (float)M_PI;
		surround.upperVerticalAngle = (float)M_PI * 0.5f;
		surround.lowerVerticalAngle = -(float)M_PI * 0.5f;

		// Hilfe-Tafel: hängt an der Leinwand, unten und etwas näher, leicht zum Kopf geneigt.
		XrCompositionLayerQuad hint = {XR_TYPE_COMPOSITION_LAYER_QUAD};
		hint.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
		hint.space = app.localSpace;
		hint.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
		hint.subImage.swapchain = app.hintSwapchain;
		hint.subImage.imageRect.extent.width = app.hintWidth;
		hint.subImage.imageRect.extent.height = app.hintHeight;
		hint.pose.orientation = cylinder.pose.orientation;
		{
			// Versatz (0, -0.42, -1.25) im Raum der Leinwand
			const XrQuaternionf &q = cylinder.pose.orientation;
			const float v[3] = {0.0f, -0.42f, -1.25f};
			// v' = v + 2w(q x v) + 2 q x (q x v)
			float cx = q.y * v[2] - q.z * v[1], cy = q.z * v[0] - q.x * v[2], cz = q.x * v[1] - q.y * v[0];
			float dx = q.y * cz - q.z * cy, dy = q.z * cx - q.x * cz, dz = q.x * cy - q.y * cx;
			hint.pose.position.x = app.screen.pos[0] + v[0] + 2.0f * (q.w * cx + dx);
			hint.pose.position.y = app.screen.pos[1] + v[1] + 2.0f * (q.w * cy + dy);
			hint.pose.position.z = app.screen.pos[2] + v[2] + 2.0f * (q.w * cz + dz);
		}
		hint.size.width = 1.15f;
		hint.size.height = app.hintWidth > 0 ? 1.15f * (float)app.hintHeight / (float)app.hintWidth : 0.3f;

		const XrCompositionLayerBaseHeader *layers[3];
		uint32_t layerCount = 0;
		bool withSurround = app.envSwapchain != XR_NULL_HANDLE && ambient > 0.005f;
		if(withSurround)
			layers[layerCount++] = (const XrCompositionLayerBaseHeader *)&surround;
		layers[layerCount++] = (const XrCompositionLayerBaseHeader *)&cylinder;
		if(app.hintSwapchain != XR_NULL_HANDLE && app.hintVisible)
			layers[layerCount++] = (const XrCompositionLayerBaseHeader *)&hint;
		XrFrameEndInfo end = {XR_TYPE_FRAME_END_INFO};
		end.displayTime = frameState.predictedDisplayTime;
		end.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
		end.layerCount = frameState.shouldRender ? layerCount : 0;
		end.layers = layers;
		XrResult endResult = xrEndFrame(app.session, &end);
		if(XR_FAILED(endResult) && withSurround)
		{
			// Das Headset lehnt einen der Zusätze ab. Ohne gültiges Bild zeigt das System nur
			// seinen Ladekreis, deshalb sofort mit der schlichten Leinwand weitermachen.
			LOGE("xrEndFrame mit Umgebung fehlgeschlagen: %d. Schalte die Umgebung ab.", (int)endResult);
			xrDestroySwapchain(app.envSwapchain);
			app.envSwapchain = XR_NULL_HANDLE;
			const XrCompositionLayerBaseHeader *plain[] = {(const XrCompositionLayerBaseHeader *)&cylinder};
			end.layerCount = frameState.shouldRender ? 1 : 0;
			end.layers = plain;
			endResult = xrEndFrame(app.session, &end);
		}
		if(XR_FAILED(endResult))
		{
			static int reported = 0;
			if(reported++ < 5)
				LOGE("xrEndFrame fehlgeschlagen: %d", (int)endResult);
		}
	}

	if(app.envSwapchain != XR_NULL_HANDLE)
		xrDestroySwapchain(app.envSwapchain);
	if(app.hintSwapchain != XR_NULL_HANDLE)
		xrDestroySwapchain(app.hintSwapchain);
	if(app.videoSwapchain != XR_NULL_HANDLE)
		xrDestroySwapchain(app.videoSwapchain);
	if(app.session != XR_NULL_HANDLE)
		xrDestroySession(app.session);
	if(app.instance != XR_NULL_HANDLE)
		xrDestroyInstance(app.instance);
	shutdownEgl(app);

	env->CallVoidMethod(app.activity, app.onExit);
	if(env->ExceptionCheck())
		env->ExceptionClear();
	env->DeleteGlobalRef(frameArray);
	if(app.videoSurface)
		env->DeleteGlobalRef(app.videoSurface);
	app.vm->DetachCurrentThread();
}

} // namespace

extern "C" {

static void takeImage(JNIEnv *env, jobject buffer, jint w, jint h, std::vector<uint8_t> &pixels, int &outW, int &outH)
{
	if(!buffer || w <= 0 || h <= 0)
		return;
	const uint8_t *data = (const uint8_t *)env->GetDirectBufferAddress(buffer);
	jlong size = env->GetDirectBufferCapacity(buffer);
	if(!data || size < (jlong)w * h * 4)
		return;
	pixels.assign(data, data + (size_t)w * h * 4);
	outW = w;
	outH = h;
}

JNIEXPORT void JNICALL Java_com_metallic_chiaki_vr_XrBridge_nativeShowHint(JNIEnv *, jobject, jboolean visible)
{
	if(g_app)
		g_app->hintVisible = visible;
}

JNIEXPORT void JNICALL Java_com_metallic_chiaki_vr_XrBridge_nativeStart(JNIEnv *env, jobject, jobject activity,
	jobject environment, jint envWidth, jint envHeight, jobject hint, jint hintWidth, jint hintHeight)
{
	if(g_app)
		return;
	App *app = new App();
	takeImage(env, environment, envWidth, envHeight, app->envPixels, app->envWidth, app->envHeight);
	takeImage(env, hint, hintWidth, hintHeight, app->hintPixels, app->hintWidth, app->hintHeight);
	app->hintVisible = true;
	env->GetJavaVM(&app->vm);
	app->activity = env->NewGlobalRef(activity);
	jclass cls = env->GetObjectClass(activity);
	app->onFrame = env->GetMethodID(cls, "onXrFrame", "([F)V");
	app->onSurface = env->GetMethodID(cls, "onXrSurface", "(Landroid/view/Surface;)V");
	app->onExit = env->GetMethodID(cls, "onXrExit", "()V");
	g_app = app;
	app->thread = std::thread(threadMain, app);
}

JNIEXPORT void JNICALL Java_com_metallic_chiaki_vr_XrBridge_nativeStop(JNIEnv *env, jobject)
{
	App *app = g_app;
	if(!app)
		return;
	app->stopRequested = true;
	if(app->thread.joinable())
		app->thread.join();
	env->DeleteGlobalRef(app->activity);
	g_app = nullptr;
	delete app;
}

JNIEXPORT void JNICALL Java_com_metallic_chiaki_vr_XrBridge_nativeHaptic(JNIEnv *, jobject, jint hand, jfloat amplitude, jint durationMs)
{
	App *app = g_app;
	if(!app || hand < 0 || hand > 1)
		return;
	app->haptic.amplitude[hand] = amplitude;
	app->haptic.durationMs[hand] = durationMs;
	app->haptic.pending[hand] = true;
}

JNIEXPORT void JNICALL Java_com_metallic_chiaki_vr_XrBridge_nativeRecenter(JNIEnv *, jobject)
{
	if(g_app)
		g_app->recenterRequested = true;
}

JNIEXPORT void JNICALL Java_com_metallic_chiaki_vr_XrBridge_nativeSetFollow(JNIEnv *, jobject, jboolean follow, jfloat tau)
{
	if(!g_app)
		return;
	g_app->screen.follow = follow;
	g_app->screen.followTau = tau;
	// Beim Umschalten einmal sauber vor den Kopf setzen.
	g_app->recenterRequested = true;
}

JNIEXPORT void JNICALL Java_com_metallic_chiaki_vr_XrBridge_nativeSetAmbient(JNIEnv *, jobject, jfloat ambient)
{
	if(g_app)
		g_app->screen.ambient = ambient;
}

JNIEXPORT void JNICALL Java_com_metallic_chiaki_vr_XrBridge_nativeSetScreen(JNIEnv *, jobject, jfloat radius, jfloat angle, jfloat height)
{
	if(!g_app)
		return;
	g_app->screen.radius = radius;
	g_app->screen.angle = angle;
	g_app->screen.height = height;
}

}
