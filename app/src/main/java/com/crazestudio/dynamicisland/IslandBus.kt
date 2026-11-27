package com.crazestudio.dynamicisland

/** In-process link between the notification listener, the service and the settings screen. */
object IslandBus {
    @Volatile
    var controller: IslandController? = null
}
