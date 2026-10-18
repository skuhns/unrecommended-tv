package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Empty notification listener. Having notification access is what lets the launcher read the TV's
 * active media sessions (now playing). Granted over adb by build-and-install.sh.
 */
public class MediaListener extends NotificationListenerService {
}
