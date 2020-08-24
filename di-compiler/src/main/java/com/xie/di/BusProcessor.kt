package com.xie.di

import com.google.auto.service.AutoService
import com.squareup.javapoet.*
import java.io.IOException
import java.util.*
import javax.annotation.processing.*
import javax.lang.model.element.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

@Suppress("MISSING_DEPENDENCY_CLASS")
@AutoService(Processor::class)
class BusProcessor : AbstractProcessor() {

    private lateinit var filer: Filer
    private lateinit var moduleName: String

    private val busInfos = HashMap<String, BusAwareInfo>()
    private val typeMete = HashMap<String, TypeMete>()
    override fun getSupportedAnnotationTypes() = setOf(
        BusEvent::class.java.canonicalName,
        AutoWire::class.java.canonicalName,
        Service::class.java.canonicalName,
        Provide::class.java.canonicalName
    )

    override fun init(processing: ProcessingEnvironment) {
        filer = processing.filer
        moduleName = processing.options["moduleName"] ?: ""
    }

    override fun process(p0: MutableSet<out TypeElement>?, p1: RoundEnvironment): Boolean {
        val funcElements = p1.getElementsAnnotatedWith(BusEvent::class.java)
        for (e in funcElements) {
            if (e is ExecutableElement) {
                fetchFunctionInfo(e)
            }
        }
        val serviceElements = p1.getElementsAnnotatedWith(Service::class.java)
        for (e in serviceElements) {
            fetchServiceInfo(e)
        }
        val autoWireElements = p1.getElementsAnnotatedWith(AutoWire::class.java)
        for (e in autoWireElements) {
            fetchAutoWireInfo(e)
        }
        val provideElements = p1.getElementsAnnotatedWith(Provide::class.java)
        for (e in provideElements) {
            if (e is ExecutableElement) {
                fetchProvideInfo(e)
            }
        }
        for (info in busInfos) {
            creatorHandler(info.value)
        }
        generateFromBus()
        return true
    }


    private fun fetchProvideInfo(e: ExecutableElement) {
        val key = e.enclosingElement.toString()
        val info = get(key)
        val createStrategy = e.getAnnotation(Provide::class.java).createStrategy
        val returnType = e.returnType.toString()
        if (!typeMete.containsKey(returnType)) {
            typeMete[returnType] = TypeMete(createStrategy, false, key)
        }
        val provide = ProvideInfo(returnType, e.simpleName.toString())
        info.provideInfo.add(provide)
    }

    private fun fetchAutoWireInfo(e: Element) {
        val info = get(e.enclosingElement.toString())
        if (e is VariableElement) {
            val arg = e.toString()
            info.autoWire.add(AutoWireInfo(arg, e.asType().toString(), FIELD))
        } else if (e is ExecutableElement) {
            info.autoWire.add(
                AutoWireInfo(
                    e.simpleName.toString(),
                    Utils.getSignature(e),
                    FUNCTION
                )
            )
        }

    }

    private fun typeMete(): MethodSpec {
        val stringType = ClassName.get(String::class.java)
        val p = ParameterizedTypeName.get(
            ClassName.get(HashMap::class.java),
            stringType,
            ClassName.get(TypeMete::class.java)
        )
        val method = MethodSpec.methodBuilder("loadTypeMete")
            .addParameter(ParameterSpec.builder(p, "map").build())
            .addAnnotation(Override::class.java)
            .addModifiers(Modifier.PUBLIC)
        val typeMeteType = ClassName.get(TypeMete::class.java)
        for ((k, v) in typeMete) {
            method.addStatement(
                "map.put(\$S,new \$T(${v.createStrategy},${v.isService},\$S))",
                k,
                typeMeteType,
                v.canProvideFrom
            )
        }
        return method.build()
    }

    private fun eventAware(info: BusAwareInfo): MethodSpec {
        val p = ParameterizedTypeName.get(
            ClassName.get(List::class.java),
            WildcardTypeName.subtypeOf(ClassName.get(Any::class.java))
        )

        val eventAwareMethod = MethodSpec.methodBuilder("eventAware")
            .addModifiers(Modifier.PUBLIC)
            .addParameter(ParameterSpec.builder(p, "args").build())
            .addAnnotation(Override::class.java)
        for (event in info.busEvent) {
            val types =
                Utils.getFieldFromSignature(event.argsSignature).map { Utils.getClassName(it) }
                    .toTypedArray()
            if (types.size > 1) {
                val builder = StringBuilder()
                builder.append("if(args.size() == ${types.size}")
                for (index in types.indices) {
                    builder.append("&& args.get($index) instanceof \$T")
                }
                builder.append(")")
                eventAwareMethod.beginControlFlow(builder.toString(), *types)
                builder.delete(0, builder.length)
                builder.append("instance.${event.functionName}(")
                for (index in types.indices) {
                    builder.append("(\$T)args.get($index),")
                }
                builder.deleteCharAt(builder.length - 1)
                builder.append(")")
                eventAwareMethod.addStatement(builder.toString(), *types)
                    .endControlFlow()
            } else if (types.size == 1) {
                eventAwareMethod.beginControlFlow("if(args.get(0) instanceof \$T)", types[0])
                    .addStatement("instance.${event.functionName}((\$T)args.get(0))", types[0])
                    .endControlFlow()
            } else if (types.isEmpty()) {
                throw RuntimeException("@busEvent不能在无参方法中执行")
            }
        }
        return eventAwareMethod.build()
    }

    private fun createMethod(info: BusAwareInfo): MethodSpec {
        //创建接收者的方法 create
        val instanceType = Utils.getClassName(info.receiverClass)

        val creatorMethod = MethodSpec.methodBuilder("create")
            .addModifiers(Modifier.PUBLIC)
            .returns(instanceType)
            .addAnnotation(Override::class.java)
        val signature = info.service?.argsSignature
        if (signature.isNullOrEmpty()) {
            creatorMethod
                .addStatement("instance = new \$T()", instanceType)
                .addStatement("autoWire()")
        } else {
            val types =
                Utils.getFieldFromSignature(signature).map { Utils.getClassName(it) }.toTypedArray()
            multiArgsGenerate(
                creatorMethod,
                types,
                "args",
                "instance = new ${instanceType.simpleName()}"
            )
            val e = ClassName.get(RuntimeException::class.java)
            creatorMethod.beginControlFlow("else")
                .addStatement(
                    "throw new \$T(\$S)",
                    e,
                    "无法创建此类${instanceType.canonicalName()}，非空参数不足,\n所需参数不能在本类或者创建它的类中@provide获得"
                )
                .endControlFlow()
        }
        creatorMethod.addStatement("return instance")
        return creatorMethod.build()
    }

    private fun multiArgsGenerate(
        methodSpec: MethodSpec.Builder,
        typeNames: Array<ClassName>,
        argsName: String,
        realTry: String
    ) {
        methodSpec.addStatement("Object $argsName[] = new Object[${typeNames.size}]")
        for (index in typeNames.indices) {
            methodSpec.addStatement(
                "$argsName[$index]= fetcher.fetch(\$S)",
                typeNames[index].canonicalName()
            )
        }
        val builder = StringBuilder()
        builder.append("if(")
        for (index in typeNames.indices) {
            builder.append("$argsName[$index] instanceof \$T &&")
        }
        builder.deleteCharAt(builder.length - 1)
        builder.deleteCharAt(builder.length - 1)
        builder.append(")")
        methodSpec.beginControlFlow(builder.toString(), *typeNames)
        builder.delete(0, builder.length)
        builder.append(realTry)
        builder.append("(")
        for (index in typeNames.indices) {
            builder.append("(\$T)$argsName[$index],")
        }
        builder.deleteCharAt(builder.length - 1)
        builder.append(")")
        methodSpec.addStatement(builder.toString(), *typeNames)
            .endControlFlow()
    }

    private fun autoWireMethod(info: BusAwareInfo): MethodSpec {

        //autoWire
        val autoWireMethod = MethodSpec.methodBuilder("autoWire")
            .addAnnotation(Override::class.java)
            .addModifiers(Modifier.PUBLIC)

        for (autoWire in info.autoWire) {
            val instanceType = Utils.getFieldFromSignature(autoWire.argsSignature)
            if (instanceType.size == 1) {
                val typeName = Utils.getClassName(instanceType[0])
                val returnName = "${autoWire.name}_fun"
                autoWireMethod.addStatement(
                    "Object $returnName = fetcher.fetch(\$S)",
                    autoWire.argsSignature
                )
                if (autoWire.wireType == FIELD) {
                    autoWireMethod.beginControlFlow("if($returnName != null)")
                        .addStatement("instance.${autoWire.name} = (\$T)$returnName", typeName)
                        .endControlFlow()
                } else if (autoWire.wireType == FUNCTION) {
                    autoWireMethod.beginControlFlow("if($returnName != null)")
                        .addStatement("instance.${autoWire.name}((\$T)$returnName)", typeName)
                        .endControlFlow()
                }
            } else {
                val typeNames = instanceType.map { Utils.getClassName(it) }.toTypedArray()
                val argsName = "${autoWire.name}_fun"
                multiArgsGenerate(autoWireMethod, typeNames, argsName, "instance.${autoWire.name}")
            }
        }
        return autoWireMethod.build()
    }

    private fun creatorHandler(info: BusAwareInfo) {

        //创建接收者的方法 create
        val instanceType = Utils.getClassName(info.receiverClass)
        val instance = FieldSpec.builder(instanceType, "instance").build()


        val fetcherName = ClassName.get(Fetcher::class.java)
        val fetcherField = FieldSpec.builder(fetcherName, "fetcher", Modifier.PRIVATE).build()

        //没有接收者的构造函数
        val cons = MethodSpec.constructorBuilder()
            .addParameter(fetcherName, "fetcher")
            .addStatement("this.fetcher = fetcher")
            .addStatement("create()")
            .addModifiers(Modifier.PUBLIC)
            .build()

        //外部接收者的构造函数
        val outerReceiverCons = MethodSpec.constructorBuilder()
            .addParameter(fetcherName, "fetcher")
            .addParameter(ParameterSpec.builder(instanceType, "receiver").build())
            .addStatement("this.instance = receiver")
            .addStatement("this.fetcher = fetcher")
            .addStatement("autoWire()")
            .addModifiers(Modifier.PUBLIC)
            .build()

        //provide
        val objectsType = ClassName.get(Objects::class.java)
        val provideFun = MethodSpec.methodBuilder("provide")
            .addAnnotation(Override::class.java)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(ClassName.get(String::class.java), "returnTypeName")
            .returns(Any::class.java)
        for (provide in info.provideInfo) {
            provideFun.beginControlFlow(
                "if(\$T.equals(returnTypeName,\$S))",
                objectsType,
                provide.returnType
            )
                .addStatement("return instance.${provide.functionName}()")
                .endControlFlow()
        }
        provideFun.addStatement("return null")


        //返回能处理的事件类型
        val listType = ClassName.get(ArrayList::class.java)
        val meteType = ClassName.get(EventMete::class.java)
        val ep = ParameterizedTypeName.get(
            ClassName.get(HashMap::class.java), ClassName.get(String::class.java),
            ParameterizedTypeName.get(listType, meteType)
        )
        val supportEventTypeMethod = MethodSpec.methodBuilder("supportEventType")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override::class.java)
            .addParameter(ParameterSpec.builder(ep, "map").build())



        for (event in info.busEvent) {
            supportEventTypeMethod.beginControlFlow("if(map.containsKey(\$S))", event.argsSignature)
                .addStatement(
                    "map.get(\$S).add(new \$T(\$S,${event.thread}))",
                    event.argsSignature,
                    meteType,
                    info.receiverClass
                )
                .endControlFlow()
                .beginControlFlow("else")
                .addStatement("ArrayList<\$T> list = new ArrayList<\$T>()", meteType, meteType)
                .addStatement(
                    "list.add(new \$T(\$S,${event.thread}))",
                    meteType,
                    info.receiverClass
                )
                .addStatement("map.put(\$S,list)", event.argsSignature)
                .endControlFlow()
        }


        //设置接收者
        val setInstanceMethod = MethodSpec.methodBuilder("setReceiver")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override::class.java)
            .addParameter(ParameterSpec.builder(ClassName.get(Any::class.java), "receiver").build())
            .addStatement("this.instance = (\$T)receiver", instanceType)
            .build()


        val getReceiverMethod = MethodSpec.methodBuilder("getReceiver")
            .addAnnotation(Override::class.java)
            .addModifiers(Modifier.PUBLIC)
            .returns(instanceType)
            .addStatement("return instance")
            .build()

        val (_, name) = Utils.getClassNameFromPath(info.receiverClass)

        val typeName = "$BUS_PREFIX$name"

        val sp = ParameterizedTypeName.get(ClassName.get(BusCreator::class.java), instanceType)
        val type = TypeSpec.classBuilder(typeName)
            .addField(fetcherField)
            .addField(instance)
            .addSuperinterface(sp)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(createMethod(info))
            .addMethod(autoWireMethod(info))
            .addMethod(cons)
            .addMethod(getReceiverMethod)
            .addMethod(outerReceiverCons)
            .addMethod(setInstanceMethod)
            //  .addMethod(supportProvideType(info))
            .addMethod(provideFun.build())
            .addMethod(supportEventTypeMethod.build())
            .addMethod(eventAware(info))
            .build()
        try {
            JavaFile.builder(BASE_PACKAGE, type).build()
                .writeTo(filer)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun fetchServiceInfo(e: Element) {
        val createModel = e.getAnnotation(Service::class.java).createModel
        val type = if (e is TypeElement) {
            e
        } else {
            (e.enclosingElement as TypeElement)
        }
        val key = ArrayList<String>()
        val receiver = type.qualifiedName.toString()
        key.add(receiver)
        key.add(type.superclass.toString())
        key.addAll(type.interfaces.map { it.toString() })
        val info = get(type.qualifiedName.toString())
        val argsSignature = Utils.getSignature(e)
        info.service = ServiceInfo(argsSignature)
        for(k in key){
            typeMete[k] = TypeMete(createModel, true, receiver)
        }
    }


    private fun fetchFunctionInfo(e: ExecutableElement) {
        val thread = e.getAnnotation(BusEvent::class.java).threadPolicy
        val argsSignature = Utils.getSignature(e)
        val info = get(e.enclosingElement.toString())
        info.busEvent.add(BusEventInfo(e.simpleName.toString(), argsSignature, thread))
    }

    private fun get(key: String): BusAwareInfo {
        if (busInfos.containsKey(key)) {
            return busInfos[key]!!
        } else {
            val info = BusAwareInfo(key, ArrayList(), null, ArrayList(), ArrayList())
            busInfos[key] = info
            return info
        }
    }


    private fun generateFromBus() {
        val fetcherMethod = MethodSpec.methodBuilder("fetch")
            .addParameter(ParameterSpec.builder(ClassName.get(String::class.java), "name").build())
            .addParameter(
                ParameterSpec.builder(ClassName.get(Fetcher::class.java), "fetcher").build()
            )
            .addParameter(ParameterSpec.builder(ClassName.get(Any::class.java), "receiver").build())
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override::class.java)
            .returns(ClassName.get(BusCreator::class.java))
        val objectsType = ClassName.get(Objects::class.java)
        for ((key, _) in busInfos) {
            val (pkg, name) = Utils.getClassNameFromPath(key)
            val type = ClassName.get(BASE_PACKAGE, "$BUS_PREFIX$name")
            val t = ClassName.get(pkg, name)
            fetcherMethod.beginControlFlow("if(\$T.equals(\$S,name))", objectsType, key)
                .beginControlFlow("if(receiver!=null)")
                .addStatement("return new \$T(fetcher,(\$T)receiver)", type, t)
                .endControlFlow()
                .beginControlFlow("else")
                .addStatement("return new \$T(fetcher)", type)
                .endControlFlow()
                .endControlFlow()
        }
        fetcherMethod.addStatement("return null")
        val type = TypeSpec.classBuilder("${BUS_PREFIX}$moduleName")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(typeMete())
            .addSuperinterface(ClassName.get(BusCreatorFetcher::class.java))
            .addMethod(fetcherMethod.build())
            .build()
        try {
            JavaFile.builder(BASE_PACKAGE, type).build()
                .writeTo(filer)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

}