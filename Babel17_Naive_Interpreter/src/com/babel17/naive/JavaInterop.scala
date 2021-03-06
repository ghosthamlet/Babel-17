package com.babel17.naive

class JavaInterop(evaluator : Evaluator, javalibs : String) {
  
  import Values._
  import Program.Id
  import scala.collection.immutable.SortedSet
  import scala.collection.immutable.SortedMap
  import java.lang.reflect.Modifier
  import java.net.URL
  import java.net.URLClassLoader
  import java.io.File

  private def getLoader  : ClassLoader = {
    val parent = this.getClass.getClassLoader
    if (javalibs.trim == "") return parent
    def filename2url(filename : String) : URL = {
      val name = filename.trim
      if (name == "" || !name.endsWith(".jar")) null
      else {
        val f = new File(name)
        if (f.isFile && f.exists)
          f.toURL
        else null
      }
    }
    val jars = javalibs.split(';').map(filename2url).filter (_ != null)
    URLClassLoader.newInstance(jars, parent);    
  }
  
  val loader = getLoader
  
  def loadClass(name : String) : Class[_] = {
    Class.forName(name, true, loader)
  }
  
  
  abstract class JavaType 
  
  case class JVoid() extends JavaType
  case class JByte(primitive : Boolean) extends JavaType
  case class JShort(primitive : Boolean) extends JavaType
  case class JInt(primitive : Boolean) extends JavaType
  case class JLong(primitive : Boolean) extends JavaType
  case class JBigInt() extends JavaType
  case class JFloat(primitive : Boolean) extends JavaType
  case class JDouble(primitive : Boolean) extends JavaType
  case class JBoolean(primitive : Boolean) extends JavaType
  case class JChar(primitive : Boolean) extends JavaType
  case class JString() extends JavaType
  case class JArray(elemType : JavaType) extends JavaType
  case class JObject(c : java.lang.Class[_]) extends JavaType
    
  def classOf(ty : JavaType) : Class[_] = {
    import com.babel17.naive.PrimitiveTypes._
    ty match {
      case JByte(false) => Byte_class
      case JByte(true) => byte_class
      case JShort(false) => Short_class
      case JShort(true) => short_class
      case JInt(false) => Int_class
      case JInt(true) => int_class
      case JLong(false) => Long_class
      case JLong(true) => long_class
      case JFloat(false) => Float_class
      case JFloat(true) => float_class
      case JDouble(false) => Double_class
      case JDouble(true) => double_class
      case JBoolean(false) => Boolean_class
      case JBoolean(true) => boolean_class
      case JChar(false) => Char_class
      case JChar(true) => char_class
      case JString() => string_class
      case JObject(c) => c
      case JArray(elemType) =>
        java.lang.reflect.Array.newInstance(classOf(elemType), 0).getClass        
    }
  }
  
  private val jobject = JObject(PrimitiveTypes.object_class)
  
  def nativeError(x:Any) : Value = {
    def exc(x : Throwable) : Value = {
      val name = x.getClass.getSimpleName.toUpperCase
      val msg = x.getMessage
      ConstructorValue(Program.Constr(name), StringValue(msg))
    }
    var cause : Value = Values.nil
    x match {
      case x: java.lang.reflect.InvocationTargetException =>
        cause = exc(x.getTargetException)
      case x: Throwable =>
        cause = exc(x)
      case _ => cause = StringValue(x.toString)
    }
    dynamicException(CONSTRUCTOR_NATIVEERROR, cause)
  }
  
  case class JavaTypeArgs(args : List[JavaType], vararg : Option[JavaType]) {
    def length = args.length + (if (vararg.isDefined) 1 else 0)
  }
  
  abstract class JMember(_name : String) {
    val name : String = if (_name == null) null else _name.toLowerCase   
  }
  case class JConstructor(c : java.lang.reflect.Constructor[_], args : JavaTypeArgs) extends JMember(null) 
  case class JMethod(m : java.lang.reflect.Method, isStatic : Boolean, args : JavaTypeArgs, returnType : JavaType) extends JMember(m.getName())
  case class JField(f : java.lang.reflect.Field, isStatic : Boolean, returnType : JavaType) extends JMember(f.getName())
  case class JInnerClass(c : java.lang.Class[_]) extends JMember(c.getSimpleName()) 
  class JClash(name : String) extends JMember(name)
  
  def canHandleArgs(args : JavaTypeArgs, count : Int) : Boolean = {
    if (args.vararg.isDefined)
      count >= args.args.length
    else
      count == args.args.length
  }
    
  def collectJConstructors(classObj : Class[_]) : List[JConstructor] =
  {
    var constructors : List[JConstructor] = List()
    for (c <- classObj.getConstructors) {
      val jargs = makeJTypeArgs(c.getParameterTypes, c.isVarArgs)
      constructors = JConstructor(c, jargs) :: constructors
    }
    constructors
  }


  def collectJMembers(classObj : Class[_]) : SortedMap[String, List[JMember]] = {
    var members : SortedMap[String, List[JMember]] = SortedMap()
    for (f <- classObj.getFields) {
      val jf = JField(f, (f.getModifiers & Modifier.STATIC) != 0,
                      makeJType(f.getType))
      if (!members.contains(jf.name)) {
        members = members + (jf.name -> List(jf))
      } else {
        members = members + (jf.name -> List(new JClash(jf.name)))
      }
    }
    for (c <- classObj.getClasses) {
      val ic = JInnerClass(c)
      if (!members.contains(ic.name))
        members = members + (ic.name -> List(ic))
      else
        members = members + (ic.name -> List(new JClash(ic.name)))
    }
    for (m <- classObj.getMethods) {
      val jm = JMethod(m, (m.getModifiers & Modifier.STATIC) != 0,
                       makeJTypeArgs(m.getParameterTypes, m.isVarArgs),
                       makeJType(m.getReturnType))
      if (!members.contains(jm.name)) {
        members = members + (jm.name -> List(jm))
      } else {
        val l = members(jm.name) match {
          case List() => List(jm)
          case (_ : JField)::xs => jm::xs
          case (_ : JInnerClass)::xs => jm::xs
          case (_ : JClash)::xs => jm::xs
          case xs => jm::xs
        }
        members = members + (jm.name -> l)
      }
    }
    members
  }
  
  def getClassDescr(name : String) : Option[ClassDescr] = {
    try {
      val c = loadClass(name)
      Some(getClassDescr(c))
    } catch {
      case _:Exception => None
    }
  }
  
  def makeJTypeArgs(t : Array[Class[_]], isVarArgs : Boolean) : JavaTypeArgs = {
    if (!isVarArgs) {
      val ar = t.toList.map(makeJType _)
      JavaTypeArgs(ar, None)
    } else {
      val ar = t.slice(0, t.length-1).toList.map(makeJType _)
      makeJType(t(t.length - 1)) match {
        case JArray(e) => JavaTypeArgs(ar, Some (e))
      }
    }
  }
  
  
  def makeJType(t : java.lang.reflect.Type) : JavaType = {
    def upperBound(ts : Array[java.lang.reflect.Type]) : JavaType = {
      if (ts == null || ts.length != 1) 
        jobject
      else 
        makeJType(ts(0))
    }
    t match {
      case c: Class[_] =>
        c.getName match {
          case "void" => JVoid()
          case "boolean" => JBoolean(true)
          case "java.lang.Boolean" => JBoolean(false)
          case "byte" => JByte(true)
          case "java.lang.Byte" => JByte(false)
          case "char" => JChar(true)
          case "java.lang.Char" => JChar(false)
          case "short" => JShort(true)
          case "java.lang.Short" => JShort(false)
          case "int" => JInt(true)
          case "java.lang.Integer" => JInt(false)
          case "long" => JLong(true)
          case "java.lang.Long" => JLong(false)
          case "float" => JFloat(true)
          case "java.lang.Float" => JFloat(false)
          case "double" => JDouble(true)
          case "java.lang.Double" => JDouble(false)
          case "java.lang.String" => JString()
          case "java.math.BigInteger" => JBigInt()
          case _ =>
            if (c.isArray) {
              JArray(makeJType(c.getComponentType))
            } else {
              JObject(c)
            }
        }
      case c: java.lang.reflect.GenericArrayType => 
        JArray(makeJType(c.getGenericComponentType))
      case p: java.lang.reflect.ParameterizedType =>
        makeJType(p.getRawType())
      case tv : java.lang.reflect.TypeVariable[_] =>
        upperBound(tv.getBounds)
      case w : java.lang.reflect.WildcardType =>
        upperBound(w.getUpperBounds)
    } 
  }
  
  import java.util.concurrent.ConcurrentHashMap
  
  private val classDescrCache : ConcurrentHashMap[String, ClassDescr] = new ConcurrentHashMap() 
  private val classClassDescrCache : ConcurrentHashMap[String, ClassDescr] = new ConcurrentHashMap()
  
  def getClassDescr(v : Class[_]) : ClassDescr = {
    val key = v.getCanonicalName
    var descr : ClassDescr = classDescrCache.get(key)
    if (descr == null) {
      descr = new ClassDescr(v)
      classDescrCache.put(key, descr)
    }
    descr
  }
  
  def getClassClassDescr(v : Class[_]) : ClassDescr = {
    val key = v.getCanonicalName
    var descr : ClassDescr = classClassDescrCache.get(key)
    if (descr == null) {
      descr = new ClassDescr(v.getClass)
      val c = getClassDescr(v)
      descr.addStaticJMembers(c)
      classClassDescrCache.put(key, descr)
    }
    descr
  }
  
  def getClassDescrForObj(v : Object) : ClassDescr = {
    if (v.isInstanceOf[Class[_]]) {
      getClassClassDescr(v.asInstanceOf[Class[_]])
    } else getClassDescr(v.getClass)
  }
  
  def inrange(v : BigInt, minvalue : Long, maxvalue : Long) : Boolean = {
    val a : BigInt = BigInt(minvalue)
    val b : BigInt = BigInt(maxvalue)
    a <= v && v <= b
  }
  
  def resolveNum(arg : Value, primitive : Boolean, 
                 minValue : Long, maxValue : Long,
                 cast : Function[BigInt, Any]) : Option[Any] = 
  {
    arg.typeConvert(true, TYPE_INT) match {
      case IntegerValue(x) =>
        if (inrange(x, minValue, maxValue)) 
          Some(cast(x))
        else None
      case _ =>
        if (!primitive && arg.isNil(true)) Some(null)
        else None
    }
  }
  
  def resolve_(arg : Value, ty : JavaType) : Option[Any] = {
    //System.out.println("resolve: arg="+arg+", ty="+ty);
    val x = resolve(arg, ty)
    //System.out.println("  resolved: "+x);
    x
  }
  
  def bigint2Byte(x : BigInt) : java.lang.Object = {
    x.byteValue.asInstanceOf[java.lang.Byte]
  }
  
  def resolve(arg : Value, ty : JavaType) : Option[Any] = {
    ty match {
      case JVoid() => None
      case JByte(primitive) =>
        resolveNum(arg, primitive, Byte.MinValue, Byte.MaxValue, _.byteValue)                    
      case JShort(primitive) =>
        resolveNum(arg, primitive, Short.MinValue, Short.MaxValue, _.shortValue)        
      case JInt(primitive) =>
        resolveNum(arg, primitive, Int.MinValue, Int.MaxValue, _.intValue)
      case JLong(primitive) =>
        resolveNum(arg, primitive, Long.MinValue, Long.MaxValue, _.longValue)
      case JChar(primitive) =>
        def res_char = resolveNum(arg, primitive, Char.MinValue, Char.MaxValue, _.charValue)
        arg.force() match {
          case StringValue(s) =>
            if (s.length == 1) Some(s.charAt(0)) else res_char
          case _ =>  res_char  
        }
      case JBoolean(primitive) =>
        arg.typeConvert(true, TYPE_BOOL) match {
          case BooleanValue(x) => Some(x)
          case _ =>
            if (!primitive && arg.isNil(true)) Some(null) else None
        }
      case JBigInt() =>
        arg.typeConvert(true, TYPE_INT) match {
          case IntegerValue(x) => Some(x.bigInteger)
          case _ =>
            if (arg.isNil(true)) Some(null) else None
        }
      case JString() =>
        arg.typeConvert(true, TYPE_STRING) match {
          case StringValue(s) =>
            Some(s)
          case _ =>
            if (arg.isNil(true)) Some(null) else None
        }
      case JFloat(primitive) => 
        arg.typeConvert(true, TYPE_REAL) match {
          case IntervalArithmetic.RealValue(lo, hi) =>
            val m : Double = (lo + (hi-lo) / 2.0)
            Some(m.toFloat)
          case _ => 
            if (!primitive && arg.isNil(true)) Some(null) else None
        }
      case JDouble(primitive) => 
        arg.typeConvert(true, TYPE_REAL) match {
          case IntervalArithmetic.RealValue(lo, hi) =>
            val m : Double = (lo + (hi-lo) / 2.0)
            Some(m)
          case _ => 
            if (!primitive && arg.isNil(true)) Some(null) else None
        }
      case JObject(c) =>
        arg match {
          case NativeValue(v) =>
            try {
              Some(c.cast(v))
            } catch {
              case _: ClassCastException => None
            }          
          case _ =>
            if (arg.isNil(true)) Some(null) 
            else {
              try {
                resolveValue(arg) match {
                  case Some(x) => Some(c.cast(x))
                  case None => None
                }
              } catch {
                case _: ClassCastException => None
              }                        
            }
        }
      case JArray(elemType) =>
        arg.typeConvert(true, TYPE_VECT) match {
          case VectorValue(v) =>
            val elemClass = classOf(elemType)
            val elems = java.lang.reflect.Array.newInstance(elemClass, v.length)
            var i = 0
            while (i < v.length) {
              resolve (v(i), elemType) match {
                case None => 
                  return None
                case Some(e) =>
                  java.lang.reflect.Array.set(elems, i, e)
                  i = i + 1
              }
            }
            Some(elems)
          case _ =>
            if (arg.isNil(true)) Some(null) else None
        }        
    }
  }
  
  def resolveValueSeq(seq : Seq[Value]) : Option[Array[_]] = {
    try {
      val result = 
        for (s <- seq) 
          yield resolveValue(s).get
      Some(result.toArray)      
    } catch {
      case _ : Exception => None
    }
  }
  
  def resolveValue(arg : Value) : Option[Any] = {
    val a = arg.force()
    if (a.isNil(true)) return Some(null)
    a match {
      case IntegerValue(v) => 
        if (inrange(v, Int.MinValue, Int.MaxValue)) Some(v.intValue)
        else if (inrange(v, Long.MinValue, Long.MaxValue)) Some(v.longValue)
        else Some(v.bigInteger)
      case StringValue(s) => Some(s)
      case VectorValue(tuple) => resolveValueSeq(tuple)
      case l: ListValue => resolveValueSeq(l.toList)
      case BooleanValue(b) => Some(b)
      case NativeValue(v) => Some(v)
      case IntervalArithmetic.RealValue(lo, hi) => Some(lo+(hi-lo)/2.0)
      case _ => None
    }
  }
  
  
  def resolve(args : List[Value], jargs : JavaTypeArgs) : Option[Array[Object]] = {
    if (!canHandleArgs(jargs, args.length)) return None
    var xs = args
    val params = new Array[Object](jargs.length)
    var pindex = 0
    for (ty <- jargs.args) {
      val x = xs.head
      xs = xs.tail
      resolve_(x, ty) match {
        case None =>
          return None
        case Some(p) =>
          params(pindex) = p.asInstanceOf[AnyRef]
          pindex = pindex + 1      
      }
    }
    if (jargs.vararg.isDefined) {
      val ty = jargs.vararg.get
      val elems = java.lang.reflect.Array.newInstance(classOf(ty), args.length-pindex)
      params(pindex) = elems
      var eindex = 0
      for (x <- xs) {
        resolve_(x, ty) match {
          case None =>
            return None
          case Some(p) =>
            java.lang.reflect.Array.set(elems, eindex, p)
            eindex = eindex + 1
        }
      }
    }
    Some (params)
  }
  
  def makeReal(f : Float) : Value = {
    val v = IntervalArithmetic.makeRV(f, f)
    v match {
      case _ : IntervalArithmetic.RealValue => v
      case _ => NativeValue(f.asInstanceOf[AnyRef])
    }
  }

  def makeReal(d : Double) : Value = {
    val v = IntervalArithmetic.makeRV(d, d)
    v match {
      case _ : IntervalArithmetic.RealValue => v
      case e : ExceptionValue => e.asPersistentException
      case _ => v
    }
  }
  
  def makeValue(obj:Any) : Value = {
    if (obj == null) return nil
    obj match {
      case i: Long => IntegerValue(i)
      case i: Int => IntegerValue(i)
      case i: Short => IntegerValue(i)
      case i: Byte => IntegerValue(i)
      case i: Char => IntegerValue(i)
      case b: Boolean => BooleanValue(b)
      case i: java.math.BigInteger => IntegerValue(new BigInt(i))
      case s: String => StringValue(s)
      case f: Float => makeReal(f)
      case d: Double => makeReal(d)
      case a: Array[_] => VectorValue(a.map(makeValue (_)).toArray)
      case _ => NativeValue(obj.asInstanceOf[AnyRef])
    }
  }
  
  def makeValueOfType(obj:Any, ty : JavaType) : Value = {
    if (obj == null) return nil
    ty match {
      case JVoid() => nil
      case JByte(_) => IntegerValue(obj.asInstanceOf[Byte])
      case JShort(_) => IntegerValue(obj.asInstanceOf[Short])
      case JInt(_) => IntegerValue(obj.asInstanceOf[Int])
      case JLong(_) => IntegerValue(obj.asInstanceOf[Long])
      case JBigInt() => IntegerValue(new BigInt(obj.asInstanceOf[java.math.BigInteger]))
      case JBoolean(_) => BooleanValue(obj.asInstanceOf[Boolean])
      case JChar(_) => IntegerValue(obj.asInstanceOf[Char])
      case JString() => StringValue(obj.asInstanceOf[String])
      case JObject(c) => makeValue(obj)
        //NativeValue(obj.asInstanceOf[AnyRef])
      case JArray(elemType) =>
        VectorValue(obj.asInstanceOf[Array[_]].map(makeValueOfType(_, elemType)))
      case JFloat(primitive) => makeReal(obj.asInstanceOf[Float])
      case JDouble(primitive) => makeReal(obj.asInstanceOf[Double])        
    }
  }
  
  def mergeJMembers(jmembers1 : SortedMap[String, List[JMember]],
                    jmembers2 : SortedMap[String, List[JMember]]) : SortedMap[String, List[JMember]] =
  {
    var newMembers = jmembers1
    for ((name, l2) <- jmembers2) {
      jmembers1.get(name) match {
        case None => newMembers = newMembers + (name -> l2)
        case Some(l1) => newMembers = newMembers + (name -> (l2 ++ l1).toList)
      }
    }
    newMembers
  }
  
  class ClassDescr(classObj : java.lang.Class[_]) {
    
    def classname = classObj.getName 
    
    private var jconstructors = collectJConstructors(classObj)
    private var jmembers = collectJMembers(classObj)
    
    def newInstance(args : List[Value]) : Value = {
      //System.out.println("new instance("+classname+"), args = "+args)
      for (jc <- jconstructors) {
        resolve(args, jc.args) match {
          case None =>
          case Some(params) =>
            try {
              jc.c.newInstance(params:_*) match {
                case o: java.lang.Object =>
                  return NativeValue(o)
                case x => 
                  return nativeError(x)
              }
            } catch {
              case x:Exception => 
                //x.printStackTrace()
                //System.out.println("NATIVEERROR, x = "+x)                
                return nativeError(x)
            }
        }
      }
      dynamicException(CONSTRUCTOR_DOMAINERROR)     
    }
    
    def addStaticJMembers(classDescr : ClassDescr) {
      var newJMembers : SortedMap[String, List[JMember]] = SortedMap()
      for ((name, members) <- classDescr.jmembers) {
        var l : List[JMember] = List()
        for (jmember <- members) {
          jmember match {
            case jf: JField => 
                if (jf.isStatic) {
                  l = jf :: l
                }
            case jm: JMethod => {
                if (jm.isStatic) {
                  l = jm :: l
                }
            }
            case jc: JInnerClass => 
                l = jc :: l
            case clash : JClash =>
          }
        }
        newJMembers = newJMembers + (name -> l.reverse)
      }
      jmembers = mergeJMembers(jmembers, newJMembers)
    }
    
    def sendMessage(v : NativeValue, message : Id) : Value = {
      jmembers.get(message.name) match {
        case None => 
          null
        case Some(members) =>
          for (member <- members) {
            member match {
              case clash : JClash => 
                return dynamicException(CONSTRUCTOR_NATIVECLASH, StringValue(clash.name))
              case f: JField => return evalField(v, f)
              case ic: JInnerClass => return evalInnerClass(v, ic)
              case m: JMethod => return NativeFunctionValue(evalMethod(v, members,_))
            }
          }
          null
      }
    }
    
    def evalMethod(v : NativeValue, ms : List[JMember], arg : Value) : Value = {
      var args : List[Value] = null
      arg.typeConvert(true, TYPE_VECT) match {
        case VectorValue(tuple) =>
          args = tuple.toList
        case _ =>
          args = List(arg)
      }
      for (m <- ms) {
        m match {
          case jm : JMethod =>
            resolve(args, jm.args) match {
              case None =>
              case Some(params) =>
                val u = if (jm.isStatic) null else v.v
                try {
                  //System.out.println("invoke method "+jm.m+", params = "+params.toList+", u = "+u)
                  val r = jm.m.invoke(u, params:_*)
                  return makeValueOfType(r, jm.returnType)
                } catch {
                  case x:Exception => return nativeError(x)                    
                }
            }
        }
      }
      null
    }
    
    def evalInnerClass(v : NativeValue, ic : JInnerClass) : Value = {
      NativeValue(ic.c)
    }
    
    def evalField(v : NativeValue, f : JField) : Value = {
      try {
        val u = if (f.isStatic) null else v.v
        makeValueOfType(f.f.get(u), f.returnType)
      } catch {
        case x:Exception => nativeError(x)
      }
    }
    
  }
  
  case class NativeValue(v : java.lang.Object) extends Value {
    
    val classDescr : ClassDescr = getClassDescrForObj(v)
  
    def typeof = Values.TYPE_NATIVE
    
    def compareToValue(that : Value) : Int = {
      import CompareResult._
      try {
        that match {
          case NativeValue(w) =>
            if (v.isInstanceOf[java.lang.Comparable[java.lang.Object]]) {
              val vc : java.lang.Comparable[java.lang.Object] = v.asInstanceOf[java.lang.Comparable[java.lang.Object]]
              val r = vc.compareTo(w)
              if (r < 0) LESS
              else if (r == 0) EQUAL
              else GREATER           
            } else {
              if (v.equals(w)) EQUAL else UNRELATED
            }
          case _ => UNRELATED
        }
      } catch {
        case _:Exception => UNRELATED
      }
    }
        
    def stringDescr(brackets : Boolean) : String = {
      val s = "_native "+v.toString
      if (brackets) "(" + s+ ")" else s
    }
    
    override def sendMessage(message : Program.Id) : Value = {
      message.name match {
        case CONVERSION_STRING => 
          try {
            val x = v.toString
            if (x == null) null else StringValue(x)
          } catch {
            case _:Exception => null
          }
        case _ => classDescr.sendMessage(this, message)
      }
    }

  } 
  
  def evalNew (_v : Value) : Value = {
    val v = _v.force
    var args : List[Value] = List()
    var classname : String = null
    var x : ExceptionValue = null
    var classDescr : Option[ClassDescr] = None 
    def analyze(v : VectorValue) {
      val tuple = v.tuple  
      if (tuple.length == 0) return
      tuple(0).force() match {
        case e: ExceptionValue => x = e
        case s: StringValue =>
          classname = s.v
          classDescr = getClassDescr(classname)
          args = tuple.tail.toList
        case NativeValue(c:Class[_]) =>
          classname = c.getCanonicalName
          classDescr = getClassDescr(classname)
          args = tuple.tail.toList
        case _ =>
      }
    }
    v match {
      case e: ExceptionValue => return e.asDynamicException
      case s: StringValue =>
        classname = s.v
        classDescr = getClassDescr(classname)
      case NativeValue(c:Class[_]) =>
        classname = c.getCanonicalName
        classDescr = getClassDescr(classname)        
      case vect: VectorValue => analyze(vect)
      case _ =>
        val w = v.typeConvert(true, Values.TYPE_VECT)
        w match {
          case vect: VectorValue => analyze(vect)
          case _ => return domainError
        }        
    }
    if (classDescr.isDefined) {
      classDescr.get.newInstance(args)
    } else {
      if (x != null)
        x.asDynamicException
      else if (classname == null)
        domainError
      else 
        dynamicException(CONSTRUCTOR_CLASSNOTFOUND, StringValue(classname))
    }
  }
  
  def evalNewClassObj(v : Value) : Value = {
    v.typeConvert(true, TYPE_STRING) match {
      case StringValue(s) => 
        try {
          val c = loadClass(s)
          NativeValue(c)
        } catch {
          case x: ClassNotFoundException => 
            nativeError(x)
        }
      case x:ExceptionValue => x.asDynamicException
      case _ => domainError
    }
  }  


}
