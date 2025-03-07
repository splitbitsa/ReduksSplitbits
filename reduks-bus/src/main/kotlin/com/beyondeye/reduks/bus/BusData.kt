package com.beyondeye.reduks.bus

import com.beyondeye.reduks.pcollections.HashTreePMap
import com.beyondeye.reduks.pcollections.PMap
import java.io.Serializable

class BusData(val data: PMap<String, Any>) : Serializable {
    companion object {
        val empty = BusData(HashTreePMap.empty())
    }
    fun  plus(key: String, newData: Any): BusData = BusData(data.plus(key, newData))
    fun  minus(key: String): BusData = BusData(data.minus(key))
    operator fun  get(key: String): Any? = data.get(key)
}