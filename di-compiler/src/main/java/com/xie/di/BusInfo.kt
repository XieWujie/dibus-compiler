package com.xie.di

data class BusEventInfo(val functionName:String, val argsSignature:String, val thread:Int)

data class ServiceInfo(val argsSignature: String)

data class EventMete(val receiver:String,val thread:Int)

data class AutoWireInfo(val name: String,val argsSignature: String,val wireType:Int)

data class ProvideInfo(val returnType:String,val functionName: String)

internal const val FUNCTION = 1
internal const val FIELD = 0


data class BusAwareInfo(val receiverClass: String,
                        val autoWire: ArrayList<AutoWireInfo>,var service:ServiceInfo?,
                        val busEvent:ArrayList<BusEventInfo>,
                         val provideInfo:ArrayList<ProvideInfo>)


data class TypeMete(val createStrategy:Int,val isService:Boolean = false,val canProvideFrom:String)