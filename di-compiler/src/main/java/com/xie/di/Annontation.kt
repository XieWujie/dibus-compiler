package com.xie.di

const val CREATE_PER = 4 //每次autoWire或者内部调用都会new 一次，实例会用 ReferenceQueue保存起来
const val CREATE_SINGLETON = 5 //优先从已有的实例获取，如果没有，则会从@provide 返回的对象和@Service标识的类中获取
const val CREATE_SCOPE = 6 //强引用保存，除非用户自己创建多个，不然容器内只会存在一个实例


/**
 * 自动创建对象
 * 可以在类和构造函数中使用，需要的参数不能在本类中@provide，会造成死递归，也不能在尚未初始化完成的类中@provide
 * <p>此时@provide 还未生效
 * @param createModel 创建类的模式
 */
@Target(AnnotationTarget.CLASS,AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
annotation class Service(val createModel:Int = CREATE_SCOPE)

/**
 * 能注入@Service本身和直接接口和直接父类
 * 在public属性或者方法中使用，kotlin中在属性中使用会有错误，因为kotlin属性默认非public
 */
@Target(AnnotationTarget.FIELD,AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class AutoWire

//在public方法中使用
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class BusEvent(val threadPolicy: Int = 0)


//在public方法中使用，如果在@service标识的类中使用，provide会自动生效，不需要提前初始化对象
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Provide(val createStrategy:Int = CREATE_SCOPE)


const val THREAD_POLICY_MAIN = 1
const val THREAD_POLICY_DEFAULT = 0