package `in`.shvms.trackme.ui.history

import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.MapType
import `in`.shvms.trackme.ui.localization.AppStrings

/**
 * How much of the basemap's text an exported image keeps.
 *
 * ### Why "Hide places" did not hide places
 *
 * The old option applied a style that turned off `poi` and transit label icons, and nothing else.
 * In Maps' vocabulary a *POI* is a business or landmark — a mall, a hospital, a park. The names of
 * towns and neighbourhoods are `administrative` labels, and road numbers are `road` labels, and
 * neither is a POI. So the switch did exactly what it said in the SDK's terms while leaving
 * "Varanasi", "Harhua", "Rajatalab" and every highway shield on the picture, which is not what
 * anybody reads the words "hide places" to mean.
 *
 * That is now [NoPlaces], which covers administrative names as well, and there is a second step
 * past it for a picture that should carry no text at all.
 */
enum class MapLabelStyle {
    /** Everything the basemap normally shows. */
    All,

    /** No business, landmark, transit or place names. Roads keep their names and numbers. */
    NoPlaces,

    /** No text anywhere: places, roads, transit, water. Geometry and colour only. */
    NoLabels;

    fun label(strings: AppStrings): String = when (this) {
        All -> strings.mapLabelsAll
        NoPlaces -> strings.mapLabelsNoPlaces
        NoLabels -> strings.mapLabelsNone
    }

    /** The label-suppression rules alone, without the outer brackets, or null for none. */
    internal fun ruleset(): String? = when (this) {
        All -> null
        NoPlaces -> NO_PLACES_RULES
        NoLabels -> NO_LABELS_RULES
    }

    /**
     * The style to apply, or null for the unstyled basemap.
     *
     * Null on any map type other than [MapType.NORMAL]: the Maps SDK only applies styling to the
     * normal basemap, so a style handed to satellite or terrain is silently ignored. Satellite is
     * imagery without labels to begin with, so there is nothing there to hide; terrain genuinely
     * cannot have its labels removed, which is why the control is disabled rather than inert.
     */
    fun styleFor(mapType: MapType): MapStyleOptions? {
        if (mapType != MapType.NORMAL) return null
        return ruleset()?.let { MapStyleOptions("[$it]") }
    }

    companion object {
        /** Whether this map type can have its labels styled at all. */
        fun isStyleable(mapType: MapType): Boolean = mapType == MapType.NORMAL

        /**
         * The style for an export: the night basemap and the label rules, composed.
         *
         * They have to compose because both are expressed as a single `MapStyleOptions`, and
         * setting one replaces the other. Dark theme used to change only the stats panel — the map
         * under it stayed the daylight basemap, which is why a "dark" export came out as white map
         * with a dark bar across it.
         *
         * The night rules are the same `map_style_night.json` the app's own maps use, so a dark
         * export looks like the app rather than like a second, unrelated dark theme.
         */
        fun exportStyle(
            context: android.content.Context,
            mapType: MapType,
            labels: MapLabelStyle,
            darkTheme: Boolean,
        ): MapStyleOptions? {
            if (!isStyleable(mapType)) return null
            val parts = listOfNotNull(
                if (darkTheme) nightRules(context) else null,
                labels.ruleset(),
            ).filter { it.isNotBlank() }
            if (parts.isEmpty()) return null
            return runCatching { MapStyleOptions("[${parts.joinToString(",")}]") }.getOrNull()
        }

        /**
         * The night style's rules with its outer brackets removed, so they can be concatenated.
         *
         * Read from the raw resource rather than duplicated here: two copies of a 60-rule style
         * would drift, and the one that drifted would be the one nobody looked at.
         */
        private fun nightRules(context: android.content.Context): String? = runCatching {
            context.resources.openRawResource(`in`.shvms.trackme.R.raw.map_style_night)
                .bufferedReader()
                .use { it.readText() }
                .trim()
                .removePrefix("[")
                .removeSuffix("]")
                .trim()
        }.getOrNull()

        /**
         * POI covers businesses and landmarks; `administrative` covers country, state, locality
         * and neighbourhood names — the ones the previous style missed entirely. Roads keep their
         * labels here on purpose: a route picture with no road names is usually harder to place,
         * and [NoLabels] is one tap away for anyone who wants that.
         */
        private const val NO_PLACES_RULES = """
              {"featureType":"poi","stylers":[{"visibility":"off"}]},
              {"featureType":"administrative","elementType":"labels","stylers":[{"visibility":"off"}]},
              {"featureType":"transit","elementType":"labels","stylers":[{"visibility":"off"}]}
        """

        /**
         * One global rule rather than a list of feature types. `elementType: labels` with no
         * `featureType` matches every label the basemap draws, including road shields, which an
         * enumeration of feature types keeps missing one of.
         */
        private const val NO_LABELS_RULES = """
            {"elementType":"labels","stylers":[{"visibility":"off"}]}
        """
    }
}
