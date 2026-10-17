package dev.underthehood

import org.json.JSONArray
import org.json.JSONObject

object Snapshots {
    fun encode(nodes:List<Node>):JSONObject = JSONObject().put("version",1).put("nodes",JSONArray().apply {nodes.forEach {put(json(it))}})
    fun json(n:Node):JSONObject = JSONObject().put("id",n.id).put("parent",n.parent).put("name",n.name).put("folder",n.folder)
        .put("remote",n.remote ?: JSONObject.NULL).put("path",n.path ?: JSONObject.NULL).put("size",n.size).put("mime",n.mime)
        .put("starred",n.starred).put("trash",n.trash).put("modified",n.modified).put("note",n.note).put("tags",n.tags).put("hash",n.hash ?: JSONObject.NULL)
    fun node(n:JSONObject):Node = Node(id=n.getString("id"),parent=n.getString("parent"),name=n.getString("name"),folder=n.getBoolean("folder"),
        remote=if(n.isNull("remote"))null else n.getString("remote"),path=if(n.isNull("path"))null else n.getString("path"),size=n.getLong("size"),mime=n.getString("mime"),
        starred=n.getBoolean("starred"),trash=n.getBoolean("trash"),modified=n.getLong("modified"),note=n.optString("note"),tags=n.optString("tags"),hash=if(n.isNull("hash"))null else n.getString("hash"))
    fun decode(value:JSONObject):List<Node> {
        require(value.getInt("version")==1){"Unsupported snapshot version"}
        val array=value.getJSONArray("nodes");require(array.length()<=100000){"Snapshot is too large"}
        val nodes=List(array.length()){node(array.getJSONObject(it))};val map=nodes.associateBy{it.id}
        require(map.size==nodes.size && nodes.none{it.id=="root"}){"Duplicate or reserved node IDs"}
        require(nodes.map{it.parent to it.name}.distinct().size==nodes.size){"Duplicate sibling names"}
        nodes.forEach {n ->
            HoodModel.validateName(n.name);require(n.size>=0){"Invalid file size"}
            var parent=n.parent;val seen=mutableSetOf(n.id)
            while(parent!="root") {require(seen.add(parent)){"Folder cycle"};val ancestor=map[parent] ?: error("Snapshot has a missing folder");require(ancestor.folder){"Parent is not a folder"};parent=ancestor.parent}
        }
        return nodes
    }
}
