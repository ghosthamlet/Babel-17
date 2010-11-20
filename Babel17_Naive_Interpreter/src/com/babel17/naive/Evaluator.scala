package com.babel17.naive


import Program._
import Values._
import scala.collection.immutable.SortedSet
import scala.collection.immutable.SortedMap


object Evaluator {
  case class EvalX(reason : String) extends Exception
  
  case class ValueRef(var value : Value);
  
  
  case class SimpleEnvironment(nonlinear : SortedMap[Id, Value]) {
    def thaw () : Environment = {
      Environment(nonlinear, SortedMap())
    }
    def lookup(id : Id) : Value = {
      nonlinear(id)
    }
  }
  
  case class Environment(nonlinear : SortedMap[Id, Value], linear : SortedMap[Id, ValueRef]) {
    def freeze () : SimpleEnvironment = {
      SimpleEnvironment(nonlinear ++ linear.mapValues(_.value))
    }
    def bind (id : Id, v : Value) : Environment = {
      Environment(nonlinear - id, linear + (id -> ValueRef(v)))
    }
    def bind (ids : SortedMap[Id, Value]) : Environment = {
      val nl : SortedMap[Id, Value] = nonlinear -- ids.keys
      val l : SortedMap[Id, ValueRef] = linear ++ (ids.mapValues(ValueRef(_)))
      Environment(nl, l)
    
    }
    def rebind (id : Id, v : Value) : Environment = {
      linear(id).value = v
      this
    }
    def rebind (ids : SortedMap[Id, Value]) : Environment = {
      for ((id, v) <- ids)
        linear(id).value = v
      this
    }
    def define (id : Id, e : Value) : Environment = {
      linear(id).value = e
      this
    }
    def lookup(id : Id) : Value = {
      try {
        linear(id).value
      } catch {
        case _ => nonlinear(id)
      }
    }
  }
  
  def emptyEnv : Environment = {
    Environment(SortedMap.empty, SortedMap.empty)
  }
  
  abstract class MatchResult  
  case class NoMatch() extends MatchResult 
  case class DoesMatch(env : Environment) extends MatchResult
  case class MatchException(v : ExceptionValue) extends MatchResult
  
  abstract class StatementResult
  case class StatementException(de : ExceptionValue) extends StatementResult
  case class StatementCollector(env : Environment, c : CollectorValue) extends StatementResult
  
  abstract class BlockResult {
    def collect_close() : Value
  }
  case class BlockException(de : ExceptionValue) extends BlockResult  {
    override def collect_close() : Value = {
      de
    }
  }
  case class BlockCollector(c : CollectorValue) extends BlockResult {
    override def collect_close() : Value = {
      c.collect_close()
    }    
  }
}


class Evaluator {
  
  import Evaluator._
  
  val random : java.util.Random = new java.util.Random()

  /*def lookup (ids : SortedSet[Id], id : Id, linear : Boolean) {
    if (!ids.contains(id)) {
      if (linear) error(id.location, "identifier is not in linear scope")
      else error(id.location, "unknown identifier")
    }
  }*/
 
  
    
  def evaluate(env : Environment, term : Term) : Value = {
    term match {
      case block : Block =>
        evalBlock(env, new DefaultCollectorValue(), block).collect_close()
      case _ => throw EvalX("incomplete evaluate") // dummy expression
        
    }
  }
  
  def evalList(env : SimpleEnvironment, l : List[SimpleExpression]) : Value = {
    if (l.isEmpty)
      EmptyListValue()
    else {
      val h = evalSE(env, l.head)
      if (h.isDynamicException) return h.asDynamicException
      val t = evalList(env, l.tail)
      if (t.isDynamicException) return t.asDynamicException
      ConsListValue(h, t)
    }
  }

  def evalVector(env : SimpleEnvironment, l : List[SimpleExpression]) : Value = {
    val count = l.length
    val v : Array[Value] = new Array(count)
    var i = 0
    for (e <- l) {
      val x = evalSE(env, e)
      if (x.isDynamicException) return x.asDynamicException
      v(i) = x
      i = i + 1
    }
    VectorValue(v)
  }
  
  def randomBigInt(n : BigInt) : BigInt = {
    var r : BigInt = 0
    do {
      val x : java.math.BigInteger = new java.math.BigInteger(n.bitLength, random)
      r = new BigInt(x)
    } while (r >= n)
    r
  }

  
  def evalSE(env : SimpleEnvironment, se : SimpleExpression) : Value = 
  {
    se match {
      case SEId(id) => env.lookup(id)
      case SEInt(u) => IntegerValue(u)
      case SEBool(b) => BooleanValue(b)
      case SEString(u) => StringValue(u)
      case SEInfinity(p) => InfinityValue(p)
      case SEConstr(constr, se) => 
        val v = evalSE(env, se)
        val x = v.asDynamicException()
        if (x != null) x
        else ConstructorValue(constr, v)
      case SEException(se) => 
        val v = evalSE(env, se)
        val x = v.asDynamicException()
        if (x != null) x
        else ExceptionValue(true, v)
      case SEMessageSend(target, message) =>
        val v = evalSE(env, target).sendMessage(message)
        if (v == null) dynamicException(CONSTRUCTOR_INVALIDMESSAGE)
        else v
      case SERecord(messageValuePairs) =>
        var map : SortedMap[Message, Value] = SortedMap()
        for ((m, se) <- messageValuePairs) {
          val v = evalSE(env, se)
          val x = v.asDynamicException()
          if (x != null) return x
          map = map + (m -> v)
        }
        ObjectValue(map)
      case SEApply(fexpr, gexpr) =>
        var f = evalSE(env, fexpr).extractFunctionValue()
        if (f.isDynamicException()) f
        else {
          val fop = f.asInstanceOf[FunctionValue]
          val g = evalSE(env, gexpr)
          if (g.isDynamicException()) g
          else fop.apply(g)
        }
      case SECompare(operands, operators) =>
        var firstOperand = evalSE(env, operands(0)).extractRepresentative()
        if (firstOperand.isDynamicException) return firstOperand
        var operandsList = operands.tail
        var operatorsList = operators
        while (!operatorsList.isEmpty) {
          var secondOperand = evalSE(env, operandsList(0)).extractRepresentative()
          if (secondOperand.isDynamicException) return secondOperand
          var operator = operatorsList(0)
          if (!compareValuesByOp(operator, firstOperand, secondOperand)) 
            return BooleanValue(false)
          firstOperand = secondOperand
          operandsList = operandsList.tail
          operatorsList = operatorsList.tail
        }
        BooleanValue(true)
      case SECons(u, v) =>
        val xu = evalSE(env, u)
        if (xu.isDynamicException) return xu.asDynamicException
        val xv = evalSE(env, v)
        if (xv.isDynamicException) return xv.asDynamicException
        ConsListValue(xu, xv)
      case SEList(l) => evalList(env, l)
      case SEVector(l) => evalVector(env, l)
      case SERandom(e) =>
        evalSE(env, e).force() match {
          case ExceptionValue(_, p) => ExceptionValue(true, p)
          case IntegerValue(n) => 
            if (n <= 0) dynamicException(CONSTRUCTOR_DOMAINERROR)
            else IntegerValue(randomBigInt(n))
          case _ => dynamicException(CONSTRUCTOR_DOMAINERROR)            
        }
      case SELazy(e) => LazyValue(this, env, e, null)
      case _ => throw EvalX("incomplete evalSE: "+se)
    }
  }
  
  def compareValuesByOp(op : Program.CompareOp, u : Value, v : Value) : Boolean = {
    import CompareResult._
    val r = compareValues(u, v)
    op match {
      case Program.LESS_EQ() => r == LESS || r == EQUAL
      case Program.GREATER_EQ() => r == GREATER || r == EQUAL
      case Program.EQUAL() => r == EQUAL
      case Program.LESS() => r == LESS
      case Program.GREATER() => r == GREATER
      case Program.UNEQUAL() => !(r == EQUAL)
    }    
  }
  
  def evalStatement(env : Environment, coll : CollectorValue, st : Statement) : StatementResult = 
  {
    st match {
      case SVal(pat, expr) =>
        val e = evalExpression(env, expr)
        matchPattern(env, pat, e, false) match {          
          case NoMatch() => StatementException(dynamicException(CONSTRUCTOR_NOMATCH))
          case DoesMatch(newEnv) => StatementCollector(newEnv, coll)
          case MatchException(x) => StatementException(x)
        }
      case SAssign(pat, expr) =>
        val e = evalExpression(env, expr)
        matchPattern(env, pat, e, true) match {          
          case NoMatch() => StatementException(dynamicException(CONSTRUCTOR_NOMATCH))
          case DoesMatch(newEnv) => StatementCollector(newEnv, coll)
          case MatchException(x) => StatementException(x)
        }
      case SYield(expr) =>
        val e = evalExpression(env, expr)
        val x = e.asDynamicException()
        if (x != null) StatementException(x)
        else {
          val c = coll.collect_add(e)
          val cx = c.asDynamicException()
          if (cx != null) StatementException(cx)
          else StatementCollector(env, c.asInstanceOf[CollectorValue])
        }
      case _ => throw EvalX("incomplete evalStatement: "+st) // dummy expression
    }
  }
  
  def evalExpression(env : Environment, expr : Expression) : Value = {
    expr match {
      case ESimple(se) => evalSE(env.freeze(), se)
      case EBlock(block) => evalBlock(env, new DefaultCollectorValue(), block).collect_close()
      case EWith(se, block) =>
        val v = collectorValue(evalSE(env.freeze(), se))
        evalBlock(env, v, block).collect_close()        
    }
  }
  
  def evalBlock(env : Environment, coll : CollectorValue, block : Block) : BlockResult = {
    var e = env
    var c = coll
    for (st <- block.statements) {
      evalStatement(e, c, st) match {
        case StatementCollector(newEnv, newColl) =>
          e = newEnv
          c = newColl
        case StatementException(x) =>
          return BlockException(x)
      }
    }
    BlockCollector(c)
  }
    
  def matchPattern(env : Environment, pat : Pattern, v : Value, rebind : Boolean) : MatchResult = {
    pat match {
      case PId(id) =>
        val x = v.asDynamicException()
        if (x != null) MatchException(x)
        else DoesMatch(if (rebind) env.rebind(id, v) else env.bind(id, v))
      case _ => throw EvalX("incomplete matchPattern: "+pat)
    }
  }


}
