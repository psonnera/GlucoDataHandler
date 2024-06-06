package de.michelinside.glucodataauto.preferences

//noinspection SuspiciousImport
import android.R
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import de.michelinside.glucodatahandler.common.R as RC

enum class SettingsFragmentClass(val value: Int, val titleRes: Int) {
    SETTINGS_FRAGMENT(0, RC.string.menu_settings),
    SORUCE_FRAGMENT(1, RC.string.menu_sources),
    ALARM_FRAGMENT(2, RC.string.menu_alarms),
    HELP_FRAGMENT(3, RC.string.menu_help),
}
class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private val LOG_ID = "GDH.AA.SettingsActivity"
    private var titleMap = mutableMapOf<Int, CharSequence>()
    companion object {
        const val FRAGMENT_EXTRA = "fragment"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            Log.v(LOG_ID, "onCreate called for fragment " + intent.getIntExtra(FRAGMENT_EXTRA, 0) + " with instance: " + (savedInstanceState!=null) )
            super.onCreate(savedInstanceState)
            if(savedInstanceState==null) {
                when (intent.getIntExtra(FRAGMENT_EXTRA, 0)) {
                    SettingsFragmentClass.SETTINGS_FRAGMENT.value -> {
                        this.supportActionBar!!.title =
                            this.applicationContext.resources.getText(SettingsFragmentClass.SETTINGS_FRAGMENT.titleRes)
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.content, SettingsFragment())
                            .commit()
                    }

                    SettingsFragmentClass.SORUCE_FRAGMENT.value -> {
                        this.supportActionBar!!.title =
                            this.applicationContext.resources.getText(SettingsFragmentClass.SORUCE_FRAGMENT.titleRes)
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.content, SourceFragment())
                            .commit()
                    }

                    SettingsFragmentClass.ALARM_FRAGMENT.value -> {
                        this.supportActionBar!!.title =
                            this.applicationContext.resources.getText(SettingsFragmentClass.ALARM_FRAGMENT.titleRes)
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.content, AlarmFragment())
                            .commit()
                    }

                    SettingsFragmentClass.HELP_FRAGMENT.value -> {
                        this.supportActionBar!!.title =
                            this.applicationContext.resources.getText(SettingsFragmentClass.HELP_FRAGMENT.titleRes)
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.content, HelpFragment())
                            .commit()
                    }
                }
            }

            supportFragmentManager.addOnBackStackChangedListener {
                Log.v(LOG_ID, "addOnBackStackChangedListener called count=${supportFragmentManager.backStackEntryCount}")
                if (titleMap.containsKey(supportFragmentManager.backStackEntryCount)) {
                    this.supportActionBar!!.title = titleMap[supportFragmentManager.backStackEntryCount]
                }
            }
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        } catch (ex: Exception) {
            Log.e(LOG_ID, "onCreate exception: " + ex)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.popBackStackImmediate()) {
            return true
        }
        return super.onSupportNavigateUp()
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        try {
            // Instantiate the new Fragment
            Log.d(LOG_ID, "onPreferenceStartFragment called at ${supportFragmentManager.backStackEntryCount} for preference ${pref.title}")
            val args = pref.extras
            val fragment = supportFragmentManager.fragmentFactory.instantiate(
                classLoader,
                pref.fragment ?: return false
            ).apply {
                arguments = args
                setTargetFragment(caller, 0)
            }
            // Replace the existing Fragment with the new Fragment
            supportFragmentManager.beginTransaction()
                .replace(R.id.content, fragment)
                .addToBackStack(null)
                .commit()

            titleMap.put(supportFragmentManager.backStackEntryCount, this.supportActionBar!!.title!!)
            this.supportActionBar!!.title = pref.title
        } catch (ex: Exception) {
            Log.e(LOG_ID, "onCreate exception: " + ex)
        }
        return true
    }
}