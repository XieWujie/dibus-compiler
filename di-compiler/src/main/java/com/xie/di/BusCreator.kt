package com.xie.di

 interface BusCreator<T>{


    fun create():T

    fun eventAware(event:List<Any>)

    fun autoWire()

    fun getReceiver():T

    fun setReceiver(receiver: Any)

    fun provide(returnTypeName:String):Any?

    fun supportEventType(events:HashMap<String,ArrayList<EventMete>>)


}

 interface BusCreatorFetcher{

    fun fetch(name:String,fetcher: Fetcher,receiver:Any?):BusCreator<*>?

    fun loadTypeMete(map: HashMap<String,TypeMete>)
}

interface Fetcher{

    /**
     * 从容器中获取实例，如果是@service标识的类可以自动创建
     */
    fun fetch(key:String):Any?

    //注入一个实例，会自动执行@autoWire，并加入事件总线
    fun injectReceiver(any: Any)

    //添加模块，会使得该模块下@provide和@service 即刻生效，@busEvent、@autoWare需要对象传教才生效
    fun injectModule(name: String)

    fun injectModule(moduleName:String,busCreatorFetcher: BusCreatorFetcher)

    /**
     * 发送事件，可以有多个非空参数，若存在可能为空的参数，使用其他对象包装起来
     * 只有容器内存在的对象才能接受事件，不包括@service下的对象
     */
    fun sendEvent(vararg args:Any)

}

