package com.example.obdanalyzer.service

import android.service.notification.NotificationListenerService

/**
 * Service vide, nécessaire à Android pour donner l'accès « contrôle de la musique » :
 * avec lui, l'appli peut piloter directement l'appli qui joue (Spotify, radio…), ce qui marche
 * sur les autoradios où les touches multimédia simulées sont ignorées.
 */
class MediaListener : NotificationListenerService()
