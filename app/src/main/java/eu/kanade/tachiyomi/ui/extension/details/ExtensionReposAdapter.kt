package eu.kanade.tachiyomi.ui.extension.details

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.ExtensionRepoItemBinding
import eu.kanade.tachiyomi.extension.model.ExtensionRepoStatus
import eu.kanade.tachiyomi.util.view.inflate
import eu.kanade.tachiyomi.util.view.setText
import yokai.i18n.MR
import yokai.util.lang.getString

/** Displays the install source and each repo currently offering the extension. */
class ExtensionReposAdapter(
    private val onActionClick: (ExtensionRepoStatus) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var statuses = emptyList<ExtensionRepoStatus>()

    @SuppressLint("NotifyDataSetChanged")
    fun setStatuses(statuses: List<ExtensionRepoStatus>) {
        if (this.statuses == statuses) return
        this.statuses = statuses
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (statuses.isEmpty()) 0 else statuses.size + 1

    override fun getItemViewType(position: Int): Int = when (position) {
        0 -> R.layout.extension_repo_header
        else -> R.layout.extension_repo_item
    }

    override fun getItemId(position: Int): Long = when (position) {
        0 -> HEADER_ID
        else -> statuses[position - 1].repoUrl.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = parent.inflate(viewType)
        return when (viewType) {
            R.layout.extension_repo_header -> HeaderViewHolder(view)
            else -> RepoViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as? RepoViewHolder)?.bind(statuses[position - 1])
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view)

    private inner class RepoViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val binding = ExtensionRepoItemBinding.bind(view)

        fun bind(status: ExtensionRepoStatus) {
            val context = itemView.context

            binding.repoName.text = status.repoName
            binding.repoStatus.text = status.statusText(context)

            val note = status.installSourceNote(context)
            binding.repoNote.isVisible = note != null
            binding.repoNote.text = note.orEmpty()

            binding.repoWarning.isVisible = status.requiresReinstall
            binding.repoWarning.setText(MR.strings.repo_needs_reinstall)

            val actionLabel = status.actionLabel()
            binding.repoAction.isVisible = actionLabel != null
            actionLabel?.let(binding.repoAction::setText)
            binding.repoAction.setOnClickListener { onActionClick(status) }
        }
    }

    private fun ExtensionRepoStatus.statusText(context: Context): String {
        val available = available ?: return context.getString(MR.strings.repo_no_longer_offered)
        return when (action) {
            // Nothing to offer and still listed: this is where the installed version came from
            ExtensionRepoStatus.Action.NONE ->
                context.getString(MR.strings.version_, available.versionName)
            ExtensionRepoStatus.Action.UPDATE ->
                context.getString(MR.strings.repo_version_newer, available.versionName)
            ExtensionRepoStatus.Action.DOWNGRADE ->
                context.getString(MR.strings.repo_version_older, available.versionName)
            ExtensionRepoStatus.Action.SWITCH ->
                context.getString(MR.strings.repo_version_same, available.versionName)
        }
    }

    private fun ExtensionRepoStatus.installSourceNote(context: Context): String? = when {
        !isInstallSource -> null
        isInferredInstallSource -> context.getString(MR.strings.repo_installed_from_here_by_key)
        else -> context.getString(MR.strings.repo_installed_from_here)
    }

    private fun ExtensionRepoStatus.actionLabel() = when (action) {
        ExtensionRepoStatus.Action.NONE -> null
        ExtensionRepoStatus.Action.UPDATE -> MR.strings.update
        ExtensionRepoStatus.Action.SWITCH -> MR.strings.action_switch_repo
        ExtensionRepoStatus.Action.DOWNGRADE -> MR.strings.action_downgrade
    }

    private companion object {
        /** Out of reach of any repo URL's hash, which is what every other row is keyed by. */
        const val HEADER_ID = Long.MIN_VALUE
    }
}
