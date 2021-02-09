package com.dan.lndpandroid

import android.view.ContextMenu
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.dan.lndpandroid.FileItem
import com.dan.lndpandroid.MainActivity
import com.dan.lndpandroid.databinding.FileItemBinding
import kotlin.collections.ArrayList

class FilesViewAdapter( private val activity: MainActivity, private val recyclerView: RecyclerView )
    : RecyclerView.Adapter<FilesViewAdapter.ViewHolder>() {

    class ViewHolder( val binding: FileItemBinding, val parent: ViewGroup) : RecyclerView.ViewHolder( binding.root )

    private var onSelectClick: ((Int)->Unit)? = null
    private var onCreateMenuListener: ((Int, ContextMenu?, View?, ContextMenu.ContextMenuInfo?)->Unit)? = null

    val items = ArrayList<FileItem>()

    fun setOnSelectClickListener( l: (Int)->Unit ) { onSelectClick = l }
    fun setOnCreateMenuListener( l: (Int, ContextMenu?, View?, ContextMenu.ContextMenuInfo?)->Unit ) { onCreateMenuListener = l }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val holder = ViewHolder(FileItemBinding.inflate(activity.layoutInflater), parent)

        holder.binding.layoutIcon.setOnClickListener {
            onSelectClick?.invoke(holder.adapterPosition)
        }

        holder.binding.root.setOnCreateContextMenuListener { contextMenu, view, contextMenuInfo ->
            onCreateMenuListener?.invoke( holder.adapterPosition, contextMenu, view, contextMenuInfo )
        }

        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val fileItem = items[position]
        holder.binding.txtName.text = fileItem.file.name
        holder.binding.imgSelected.isVisible = fileItem.isSelected

        if (fileItem.file.isDirectory) {
            holder.binding.imgDirectory.setImageBitmap(FileCopyFragment.defaultFolderBitmap)
            holder.binding.txtDetails.isVisible = false
        } else {
            holder.binding.txtDetails.text = fileItem.details
            holder.binding.txtDetails.isVisible = true
            holder.binding.imgDirectory.setImageBitmap(fileItem.thumbnail ?: FileCopyFragment.defaultFileBitmap)
        }
    }

    override fun getItemCount(): Int = items.size
}