package com.foursquare.lint

import scala.tools.nsc.{Global}
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.symtab.Flags.{IMPLICIT, OVERRIDE, MUTABLE, CASE, LAZY, FINAL}
import com.foursquare.lint.global._

// Warning: Don't try too hard to understand this code, it's a mess and needs
// to be rewritten in a type-safe and transparent way.

class AbstractInterpretation(val global: Global, val unit: GUnit) {
  import global._

  //TODO: move these to utils
  def isUsed(tree: GTree, name: String): Boolean = {
    var used = 0

    /*
    //scala 2.10+
    for(Ident(id) <- t; if id.toString == name) used += 1
    //TODO: Only for select types, also, maybe this doesn't belong in all uses of isUsed (e.g. Assignment right after declaration)
    for(Select(Ident(id), func) <- t; if (func.toString matches "size|length|head|last") && (id.toString == name)) used -= 1
    */
    def findUsed(tree: GTree) {
      for(Ident(id) <- tree.children; if id.toString == name) used += 1
      //TODO: Only for select types, also, maybe this doesn't belong in all uses of isUsed (e.g. Assignment right after declaration)
      for(Select(Ident(id), func) <- tree.children; if (func.toString matches "size|length|head|last") && (id.toString == name)) used -= 1
      
      for(subTree <- tree.children; if (subTree != tree)) findUsed(subTree)
    }
    
    findUsed(tree)
    
    (used > 0)
  }
  
  def getUsed(tree: GTree): collection.mutable.HashSet[String] = {
    val used = collection.mutable.HashSet[String]()

    def findUsed(tree: GTree) {
      for(Ident(id) <- tree.children) used += id.toString

      for(subTree <- tree.children; if (subTree != tree)) findUsed(subTree)
    }
    
    findUsed(tree)
    
    used
  }

  def isAssigned(tree: GTree, name: String): Boolean = {
    def findUsed(tree: GTree): Boolean = {
      for(Assign(Ident(id), _) <- tree.children; if id.toString == name) return true
      var out = false
      for(subTree <- tree.children; if (subTree != tree)) out |= findUsed(subTree)
      out
    }
    
    findUsed(tree)
  }
    
  def returnCount(tree: GTree): Int = {
    var used = 0

    def findUsed(tree: GTree) {
      for(Return(id) <- tree.children) used += 1

      for(subTree <- tree.children; if (subTree != tree)) findUsed(subTree)
    }
    
    findUsed(tree)
    
    used
  }

  def checkRegex(reg: String) {
    try {
      val regex = reg.r
    } catch {
      case e: java.util.regex.PatternSyntaxException =>
        unit.warning(treePosHolder.pos, "Regex pattern syntax warning: "+e.getDescription)
      case e: Exception =>
    }
  }

  //Data structure ideas:
  //1. common trait for Value, Collection, StringAttrs
  //2. for Values: empty -> any, and add "nonValues" or "conditions" to cover
  //   if(a == 0) ..., if(a%2 == 0) ... even for huge collections

  object Values {
    lazy val empty = new Values()
    //def apply(low: Int, high: Int, name: String, isSeq: Boolean, actualSize: Int): Values = new Values(name = name, ranges = Set((low, high)), isSeq = isSeq, actualSize = actualSize)
    //def apply(s: Set[Int], name: String, isSeq: Boolean, actualSize: Int): Values = new Values(name = name, values = s, isSeq = isSeq, actualSize = actualSize)
    def apply(i: Int, name: String = ""): Values = new Values(name = name, values = Set(i))
    def apply(low: Int, high: Int): Values = new Values(ranges = Set((low, high)))
  }
  class Values(
      val ranges: Set[(Int, Int)] = Set(),
      val values: Set[Int] = Set(),
      val conditions: Set[Int => Boolean] = Set(),//Currently obeyed only ifEmpty otherwise
      val name: String = "",
      val isSeq: Boolean = false,
      val actualSize: Int = -1
    ) {

    //require(isEmpty || isValue || isSeq || (ranges.size == 1 && values.size == 0) || (ranges.size == 0 && values.size > 0))
    //println(this)
    
    def conditionsContain(i: Int): Boolean = conditions.forall(c => c(i))
    
    // experimental alternative to empty
    def makeUnknown = new Values(conditions = conditions)
   
    def rangesContain(i: Int): Boolean = (ranges exists { case (low, high) => i >= low && i <= high })
    
    def notSeq = new Values(ranges, values, conditions, name, false)
    
    def contains(i: Int): Boolean = (values contains i) || rangesContain(i)
    def apply(i: Int): Boolean = contains(i)
    //TODO: this crashes if (high-low) > Int.MaxValue - code manually, or break large ranges into several parts
    //def exists(func: Int => Boolean) = (values exists func) || (ranges exists { case (low, high) => (low to high) exists func })
    //def forall(func: Int => Boolean) = (values forall func) && (ranges forall { case (low, high) => (low to high) forall func })
    def existsLower(i: Int): Boolean = (values exists { _ < i }) || (ranges exists { case (low, high) => low < i })
    def existsGreater(i: Int): Boolean = (values exists { _ > i }) || (ranges exists { case (low, high) => high > i })
    def forallEquals(i: Int): Boolean = (values forall { _ == i }) && (ranges forall { case (low, high) => (low == high) && (i == low) })
    
    def addRange(low: Int, high: Int): Values = new Values(ranges + (if(low > high) (high, low) else (low, high)), values, conditions, name, false, -1)
    def addValue(i: Int): Values = new Values(ranges, values + i, conditions, name, false, -1)
    def addSet(s: Set[Int]): Values = new Values(ranges, values ++ s, conditions, name, false, -1) //TODO: are these false, -1 ok?
    def addCondition(c: Int => Boolean): Values = new Values(ranges, values, conditions + c, name, isSeq, actualSize)
    def addConditions(c: Set[Int => Boolean]): Values = new Values(ranges, values, conditions ++ c, name, isSeq, actualSize)
    def addName(s: String): Values = new Values(ranges, values, conditions, s, isSeq, actualSize)
    def addActualSize(s: Int): Values = new Values(ranges, values, conditions, name, isSeq, s)
    //TODO: this can go wrong in many ways - (low to high) limit, freeze on huge collection, etc
    
    def distinct: Values = {
      val t = this.optimizeValues
      //ADD: optimizeranges that makes them non-overlapping
      if(t.values.size == 0 && t.ranges.size == 1) {
        new Values(ranges = t.ranges, conditions = conditions, /*name = name,*/ isSeq = isSeq, actualSize = actualSize)
      } else {
        new Values(values = t.values ++ t.ranges.flatMap { case (low, high) => (low to high) }, conditions = conditions, /*name = name,*/ isSeq = isSeq, actualSize = actualSize)
      }
    }
    //TODO: Is this correct for weird code?
    def sum: Int = {
      val t = this.distinct
      t.values.sum + ranges.foldLeft(0)((acc, n) => acc + (n._1 to n._2).sum)
    }
    
    // discard values, that are inside ranges
    def optimizeValues: Values = new Values(values = values.filter(v => !rangesContain(v)), ranges = ranges, conditions = conditions, name = name, isSeq = isSeq, actualSize = actualSize)
    //def optimizeRanges = new Values(values = values, ranges = ranges.)
    
    def isEmpty: Boolean = this.size == 0
    def nonEmpty: Boolean = this.size > 0
    def isValue: Boolean = this.values.size == 1 && this.ranges.isEmpty // TODO: this is stupid and buggy, and woudn't exist if I didn't mix all types into one class
    def getValue: Int = if(isValue) values.head else throw new Exception()
    def isValueForce: Boolean = (this.values.size == 1 && this.ranges.isEmpty) || (ranges.size == 1 && this.size == 1)
    def getValueForce: Int = if(isValue) values.head else if(ranges.size == 1 && this.size == 1) ranges.head._1 else throw new Exception()

    def max: Int = math.max(if(values.nonEmpty) values.max else Int.MinValue, if(ranges.nonEmpty) ranges.maxBy(_._2)._2 else Int.MinValue)
    def min: Int = math.min(if(values.nonEmpty) values.min else Int.MaxValue, if(ranges.nonEmpty) ranges.minBy(_._1)._1 else Int.MaxValue)

    def dropValue(i: Int): Values = new Values(
      ranges.flatMap { case (low, high) =>
        if(i > low && i < high) List((low,i-1), (i+1,high)) else
        if(i == low && i < high) List((low+1,high)) else
        if(i > low && i == high) List((low,high-1)) else
        if(i == low && i == high) Nil else
        List((low, high))
      }, 
      values - i,
      Set(),//ADD: conditions?
      name)

    //beware of some operations over ranges.
    def map(func: Int => Int, rangeSafe: Boolean = true): Values = //ADD: conditions?
      if(rangeSafe) {
        new Values(
          ranges
            .map { case (low, high) => (func(low), func(high)) }
            .map { case (low, high) if low > high => (high, low); case (low, high) => (low, high) },
          values
            .map(func))
      } else {
        if(this.ranges.nonEmpty && this.size <= 100001) //ADD: this only checks small ranges, also can still get slow
          (new Values(values = values ++ ranges.flatMap { case (low, high) => (low to high) }, name = name, isSeq = isSeq, actualSize = actualSize)).map(func)
        else 
          Values.empty
      }
    
    def applyCond(condExpr: Tree): (Values, Values) = {//TODO: return (true, false) ValueSets in one go - applyInverseCond sucks //TODO: true and false cound be possible returns for error msgs
      //TODO: check out if you're using 'this' for things that aren't... this method shouldn't be here anyways
      //println("expr: "+showRaw(condExpr))
      var alwaysHold, neverHold = false
      val out = condExpr match {
        case Apply(Select(expr1, nme.ZAND), List(expr2)) => //&&
          this.applyCond(expr1)._1.applyCond(expr2)._1
          
        case Apply(Select(expr1, nme.ZOR), List(expr2)) => //||
          val (left,right) = (this.applyCond(expr1)._1, this.applyCond(expr2)._1)
          new Values(
            left.ranges ++ right.ranges,
            left.values ++ right.values,
            left.conditions ++ right.conditions,
            this.name)

        /// String stuff
        case strFun @ Apply(Select(string, func), params) if string.tpe != null && string.tpe.widen <:< definitions.StringClass.tpe =>
          computeExpr(strFun)
          
        case strFun @ Select(Apply(scala_augmentString, List(string)), func)
          if (scala_augmentString.toString endsWith "augmentString") =>

          computeExpr(strFun)

        //ADD: expr op expr that handles idents right
        case Apply(Select(Ident(v), op), List(expr)) if v.toString == this.name && computeExpr(expr).isValue => 
        
          val value = computeExpr(expr).getValue
          val out: Values = op match {
            case nme.EQ => 
              (if(this.nonEmpty) { 
                if(this.contains(value)) {
                  if(this.forallEquals(value)) alwaysHold = true
                  Values(value).addName(name)
                } else {
                  neverHold = true
                  this.makeUnknown
                }
              } else if(!this.conditionsContain(value)) {
                neverHold = true
                this.makeUnknown
              } else {
                this.makeUnknown
              }).addCondition(_ == value)
            case nme.NE => 
              (if(this.contains(value)) {
                val out = this.dropValue(value) 
                if(out.isEmpty) neverHold = true
                out
              } else if(this.nonEmpty) {
                alwaysHold = true
                this
              } else {
                this.makeUnknown
              }).addCondition(_ != value)
            case nme.GT => new Values(
                ranges.flatMap { case (low, high) => if(low > value) Some((low, high)) else if(high > value) Some((value+1, high)) else None },
                values.filter { _ > value },
                Set(_ > value),
                this.name)
            case nme.GE => new Values(
                ranges.flatMap { case (low, high) => if(low >= value) Some((low, high)) else if(high >= value) Some((value, high)) else None },
                values.filter { _ >= value },
                Set(_ >= value),
                this.name)
            case nme.LT => new Values(
                ranges.flatMap { case (low, high) => if(high < value) Some((low, high)) else if(low < value) Some((low, value-1)) else None },
                values.filter { _ < value },
                Set(_ < value),
                this.name)
            case nme.LE => new Values(
                ranges.flatMap { case (low, high) => if(high <= value) Some((low, high)) else if(low <= value) Some((low, value)) else None },
                values.filter { _ <= value },
                Set(_ <= value),
                this.name)
            case a => 
              //println("applyCond: "+showRaw( a ));
              this.makeUnknown
          }
          
          if((Set[Name](nme.GT, nme.GE, nme.LT, nme.LE) contains op) && this.nonEmpty) {
            if(out.isEmpty) neverHold = true
            if(out.size == this.size) alwaysHold = true
          }
          
          out          
        case Apply(Select(expr1, op), List(expr2)) =>
          val (left, right) = (computeExpr(expr1), computeExpr(expr2))
          var out = Values.empty.addActualSize(this.actualSize)
          val startOut = out
          op match {
            case nme.EQ | nme.NE =>
              if(left.isValue && right.isValue && left.getValue == right.getValue) {
                if(op == nme.EQ) alwaysHold = true else neverHold = true
                if(op == nme.EQ && (left.name == this.name || right.name == this.name)) out = this
              } else if(left.nonEmpty && right.nonEmpty && (left.min > right.max || right.min > left.max)) {
                if(op != nme.EQ) alwaysHold = true else neverHold = true
                if(op != nme.EQ && (left.name == this.name || right.name == this.name)) out = this
              } else if(left.name == this.name && right.isValueForce && op == nme.EQ) {
                out = Values(right.getValueForce).addName(this.name)
              } else if(right.name == this.name && left.isValueForce && op == nme.EQ) { //Yoda conditions 
                out = Values(left.getValueForce).addName(this.name)
              }
              if(left.isEmpty && left.conditions.nonEmpty && right.isValue && !left.conditionsContain(right.getValue)) {
                neverHold = true
              }

            case nme.GT | nme.LE => 
              if(left.isValue && right.isValue) {
                if(left.getValue > right.getValue) {
                  if(op == nme.GT) alwaysHold = true else neverHold = true
                  if(op == nme.GT && (left.name == this.name || right.name == this.name)) out = this
                } else {
                  if(op != nme.GT) alwaysHold = true else neverHold = true
                  if(op != nme.GT && (left.name == this.name || right.name == this.name)) out = this
                }
              } else if(left.nonEmpty && right.nonEmpty) {
                if(left.max <= right.min) {
                  if(op != nme.GT) alwaysHold = true else neverHold = true
                  if(op != nme.GT && (left.name == this.name || right.name == this.name)) out = this
                } else if(left.min > right.max) {
                  if(op == nme.GT) alwaysHold = true else neverHold = true
                  if(op == nme.GT && (left.name == this.name || right.name == this.name)) out = this
                }
              }
              
            case nme.GE | nme.LT => 
              if(left.isValue && right.isValue) {
                if(left.getValue >= right.getValue) {
                  if(op == nme.GE) alwaysHold = true else neverHold = true
                  if(op == nme.GE && (left.name == this.name || right.name == this.name)) out = this
                } else {
                  if(op != nme.GE) alwaysHold = true else neverHold = true
                  if(op != nme.GE && (left.name == this.name || right.name == this.name)) out = this
                }
              } else if(left.nonEmpty && right.nonEmpty) {
                if(left.max < right.min) {
                  if(op != nme.GE) alwaysHold = true else neverHold = true
                  if(op != nme.GE && (left.name == this.name || right.name == this.name)) out = this
                } else if(left.min >= right.max) {
                  if(op == nme.GE) alwaysHold = true else neverHold = true
                  if(op == nme.GE && (left.name == this.name || right.name == this.name)) out = this
                }
              }
              
            case _ =>
          }

          if(this.name == out.name) out.addConditions(this.conditions) else out
        case Select(expr, op) =>
          computeExpr(expr).applyUnary(op)
          Values.empty.addActualSize(this.actualSize)
          
        case expr =>
          //println("expr: "+showRaw(expr))
          computeExpr(expr)
          Values.empty.addActualSize(this.actualSize)
      }
      
      if(neverHold) unit.warning(condExpr.pos, "This condition will never hold.")
      if(alwaysHold) unit.warning(condExpr.pos, "This condition will always hold.")
      
      if(!isUsed(condExpr, this.name)) (this, this) else (out, if(neverHold) this else if(alwaysHold) Values.empty else this - out)
    }
    
    //TODO: does this even work? the v map is suspect and ugly
    def -(value: Values): Values = {
      if(this.isEmpty || value.isEmpty) {
        Values.empty
      } else {
        var out = this
        value map({ v => out = out.dropValue(v); v }, rangeSafe = false)
        out
      }
    }
    
    def applyUnary(op: Name): Values = op match { 
      case nme.UNARY_+ => this
      case nme.UNARY_- => this.map(a => -a)
      case nme.UNARY_~ => this.map(a => ~a, rangeSafe = false)
      case signum if signum.toString == "signum" => this.map(a => math.signum(a)).addCondition({ a => Set(-1,0,1) contains a })
      case abs if abs.toString == "abs" =>
        new Values(//ADD: conditions
          ranges
            .map { case (low, high) => (if(low > 0) low else 0, math.max(math.abs(low), math.abs(high))) }
            .map { case (low, high) if low > high => (high, low); case (low, high) => (low, high) },
          values.map(a => math.abs(a))).addCondition(_ >= 0)

      case size if (size.toString matches "size|length") => if(this.actualSize != -1) Values(this.actualSize) else Values.empty.addCondition(_ >= 0)
      case head_last if (head_last.toString matches "head|last") => 
        //Only works for one element :)
        if(this.actualSize == 0) unit.warning(treePosHolder.pos, "Taking the "+head_last.toString+" of an empty collection.")
        if(this.actualSize == 1 && this.size == 1) Values(this.getValueForce) else Values.empty
      case tail_init if (tail_init.toString matches "tail|init") && this.actualSize != -1 => 
        if(this.actualSize == 0) {
          unit.warning(treePosHolder.pos, "Taking the "+tail_init.toString+" of an empty collection.")
          Values.empty
        } else {
          Values.empty.addActualSize(this.actualSize - 1)
        }
      
      case to if (to.toString matches "toIndexedSeq|toList|toSeq|toVector") => this //only immutable
      case distinct if (distinct.toString == "distinct") && this.actualSize != -1 => 
        val out = this.distinct
        out.addActualSize(out.size)
      
      case id if (id.toString matches "reverse") => this //Will hold, while Set is used for values
      case max if (max.toString == "max") && this.nonEmpty => Values(this.max)
      case min if (min.toString == "min") && this.nonEmpty => Values(this.min)
      case sum if (sum.toString == "sum") && this.nonEmpty && this.distinct.size == this.actualSize => Values(this.sum)

      case empty if (empty.toString == "isEmpty") && (this.actualSize != -1) => 
        unit.warning(treePosHolder.pos, "This condition will " + (if(this.actualSize == 0) "always" else "never") + " hold.")
        Values.empty
      case empty if (empty.toString == "nonEmpty") && (this.actualSize != -1) => 
        unit.warning(treePosHolder.pos, "This condition will " + (if(this.actualSize > 0) "always" else "never") + " hold.")
        Values.empty

      case a => 
        //val raw = showRaw( treePosHolder );
        //println("applyUnary: op:"+op+" thisval:"+this+" tree:"+treePosHolder.toString+"\n"+raw);
        Values.empty
    }
    def apply(op: Name)(right: Values): Values = {
      val left = this

      type F = ((Int, Int) => Int, Boolean) // 2.9 typer fails at pattern matching :)

      val (func, isRangeSafe): F = op match {
          case nme.ADD => (_ + _, true): F
          case nme.SUB => (_ - _, true): F
          case nme.MUL => (_ * _, false): F //ADD: are all these really unsafe for ranges (where you only process first and last)
          case nme.AND => (_ & _, false): F
          case nme.OR  => (_ | _, false): F
          case nme.XOR => (_ ^ _, false): F
          case nme.LSL => (_ << _, false): F
          case nme.LSR => (_ >>> _, false): F
          case nme.ASR => (_ >> _, false): F
          case nme.MOD if right.size == 1 && right.getValueForce != 0 => (_ % _, false): F
          case nme.DIV if right.size == 1 && right.getValueForce != 0 => (_ / _, false): F
          case a if a.toString matches "apply|take|drop|max|min|contains|map|count" => ((a: Int, b: Int) => throw new Exception(), false): F //Foo, check below
          case _ => return Values.empty
      }
      
      //List(ValDef(Modifiers(), newTermName("a"), TypeTree(), Apply(Select(Apply(Select(Ident(scala.StringContext), newTermName("apply")), List(Literal(Constant(" hello ")), Literal(Constant(" rld ")), Literal(Constant(" lll")))), newTermName("s")), List(Apply(Select(Apply(Select(Literal(Constant("w")), newTermName("$plus")), List(Literal(Constant(3)))), newTermName("$plus")), List(Literal(Constant("o")))), Literal(Constant(10))))))
      val out: Values = (
        if(op.toString == "count") {
          (if(left.actualSize == 0) Values(0) else if(left.actualSize > 0) Values.empty.addCondition(_ < left.actualSize) else Values.empty).addCondition(_ >= 0)
        } else if(left.isEmpty || right.isEmpty) {
          //ADD: x & 2^n is Set(2^n,0) and stuff like that :)
          if(left.contains(0) || right.contains(0)) {
            if(Set[Name](nme.MUL, nme.AND) contains op) Values(0) else Values.empty
          } else {
            Values.empty
          }
        } else if(op.toString == "apply") {
          //TODO: if you wanted actual values, you need to save seq type and refactor values from Set to Seq
          if(left.isSeq && left.actualSize == 1 && left.size == 1 && right.isValueForce && right.getValueForce == 0) Values(left.getValueForce) else left
        } else if(op.toString == "map") {
          right
        } else if(op.toString == "contains") {
          if(right.isValue) {
            if(left.contains(right.getValue)) {
              unit.warning(treePosHolder.pos, "This contains will always return true")
            } else {
              unit.warning(treePosHolder.pos, "This contains will never return true")
            }
          }
          Values.empty
        } else if(op.toString == "max") {
          if(left.isValue && right.isValue) { 
            if(left.getValue >= right.getValue) {
              unit.warning(treePosHolder.pos, "This max will always return the first value")
            } else {
              unit.warning(treePosHolder.pos, "This max will always return the second value")
            }
            Values(math.max(left.getValue, right.getValue))
          } else if(left.isValue && !right.isValue) {
            if(left.getValue >= right.max) {
              unit.warning(treePosHolder.pos, "This max will always return the first value")
              Values(left.getValue)
            } else if(left.getValue <= right.min) {
              unit.warning(treePosHolder.pos, "This max will always return the second value")
              right
            } else {
              Values.empty
            }
          } else if(!left.isValue && right.isValue) {
            if(right.getValue >= left.max) {
              unit.warning(treePosHolder.pos, "This max will always return the second value")
              Values(right.getValue)
            } else if(right.getValue <= left.min) {
              unit.warning(treePosHolder.pos, "This max will always return the first value")
              left
            } else {
              Values.empty
            }
          } else {
            Values.empty
          }          
        } else if(op.toString == "min") {
          if(left.isValue && right.isValue) {
            if(left.getValue <= right.getValue) {
              unit.warning(treePosHolder.pos, "This min will always return the first value")
            } else {
              unit.warning(treePosHolder.pos, "This min will always return the second value")
            }
            Values(math.min(left.getValue, right.getValue))
          } else if(left.isValue && !right.isValue) {
            if(left.getValue <= right.min) {
              unit.warning(treePosHolder.pos, "This min will always return the first value")
              Values(left.getValue)
            } else if(left.getValue > right.max) {
              unit.warning(treePosHolder.pos, "This min will always return the second value")
              right
            } else {
              Values.empty
            }
          } else if(!left.isValue && right.isValue) {
            if(right.getValue <= left.min) {
              unit.warning(treePosHolder.pos, "This min will always return the second value")
              Values(right.getValue)
            } else if(right.getValue > left.max) {
              unit.warning(treePosHolder.pos, "This min will always return the first value")
              left
            } else {
              Values.empty
            }
          } else {
            Values.empty
          }          
        } else if(op.toString == "take") {
          if(left.isSeq && left.actualSize != -1 && right.isValue) {
            if(right.getValueForce >= left.actualSize) {
              unit.warning(treePosHolder.pos, "This take is always unnecessary.")
              this 
            } else {
              if(right.getValueForce <= 0) unit.warning(treePosHolder.pos, "This collection will always be empty.")
              Values.empty.addName(name).addActualSize(math.max(0, right.getValueForce))
            }
          } else {
            Values.empty
          }
        } else if(op.toString == "drop") {
          if(left.isSeq && left.actualSize != -1 && right.isValue) {
            if(right.getValueForce <= 0) {
              unit.warning(treePosHolder.pos, "This drop is always unnecessary.")
              this
            } else {
              if(left.actualSize-right.getValueForce <= 0) unit.warning(treePosHolder.pos, "This collection will always be empty.")
              Values.empty.addName(name).addActualSize(math.max(0, left.actualSize-right.getValueForce))
            }
          } else { 
            Values.empty
          }
        } else if(left.isValue && right.isValue) {
          if(left.getValue == right.getValue && op == nme.SUB && !left.name.isEmpty) unit.warning(treePosHolder.pos, "Same values on both sides of subtraction.")
          Values(func(left.getValue, right.getValue))
        } else if(!left.isValue && right.isValue) {
          left.map(a => func(a, right.getValue), rangeSafe = isRangeSafe)
        } else if(left.isValue && !right.isValue) {
          right.map(a => func(left.getValue, a), rangeSafe = isRangeSafe) 
        } else {
          //ADD: join ranges, but be afraid of the explosion :)
          if(left eq right) {
            op match {
              //case nme.ADD => left.map(a => a+a) //WRONG
              //case nme.MUL => left.map(a => a*a)
              case nme.DIV if(!left.contains(0)) => Values(1) //TODO: never gets executed?
              case nme.SUB | nme.XOR => 
                if(op == nme.SUB) unit.warning(treePosHolder.pos, "Same expression on both sides of subtraction.")
                Values(0)
              case nme.AND | nme.OR  => left
              case _ => Values.empty
            }
          } else {
            Values.empty
          }
        }
      )
      
      op match {
        case nme.MOD if right.isValue => out.addConditions(Set(_ > -math.abs(right.getValue), _ < math.abs(right.getValue)))
        case nme.DIV if left.isValue => out.addConditions(Set(_ >= -math.abs(left.getValue), _ <= math.abs(left.getValue)))
        /*case (nme.AND | nme.OR) if left.isValue || right.isValue => 
          //TODO: you need to learn two's complement, brah
          val (min, max) = (
            if(left.isValue && right.isValue) 
              (math.min(left.getValue, right.getValue), math.max(left.getValue, right.getValue))
            else if(left.isValue) 
              (left.getValue, left.getValue)
            else //if(right.isValue) 
              (right.getValue, right.getValue)
          )
              
          if(op == nme.AND) out.addConditions(_ <)*/
        case _ => out
      }
    }
    
    //approximate
    def size: Int = values.size + ranges.foldLeft(0)((acc, range) => acc + (range._2 - range._1) + 1)
    
    override def toString: String = "Values("+(if(name.size > 0) name+")(" else "")+(values.map(_.toString) ++ ranges.map(a => a._1+"-"+a._2)).mkString(",")+", "+isSeq+", "+actualSize+")"
  }
  
  val SeqLikeObject: Symbol = definitions.getModule(newTermName("scala.collection.GenSeq"))
  val SeqLikeClass: Symbol = definitions.getClass(newTermName("scala.collection.SeqLike"))
  val SeqLikeContains: Symbol = SeqLikeClass.info.member(newTermName("contains"))
  val SeqLikeApply: Symbol = SeqLikeClass.info.member(newTermName("apply"))
  val SeqLikeGenApply: Symbol = SeqLikeObject.info.member(newTermName("apply"))
  def methodImplements(method: Symbol, target: Symbol): Boolean = {
    method == target || method.allOverriddenSymbols.contains(target)
  }
  
  //TODO: extend with more functions... and TEST TEST TEST TEST
  def computeExpr(tree: Tree): Values = {
    treePosHolder = tree
    val out: Values = tree match {
      case Literal(Constant(value: Int)) => Values(value)
      //TODO: I don't think this ones work at all...
      case Select(This(typeT), termName) =>
        val name = termName.toString
        val n = (if(name.contains(".this.")) name.substring(name.lastIndexOf(".")+1) else name).trim
        vals(n)
      case Ident(termName) => 
        val name = termName.toString
        val n = (if(name.contains(".this.")) name.substring(name.lastIndexOf(".")+1) else name).trim
        //println(n+": "+vals(n))
        vals(n)
        
      // String size
      /*case Apply(Select(Ident(id), length), List()) if stringVals.exists(_.name == Some(id.toString)) && length.toString == "length" =>
        val exactValue = stringVals.find(_.name == Some(id.toString)).map(_.exactValue)
        exactValue.map(v => if(v.isDefined) Values(v.get.size) else Values.empty).getOrElse(Values.empty)
        
      case Select(Apply(Select(predef, augmentString), List(Ident(id))), size)
        if stringVals.exists(_.name == Some(id.toString)) && predef.toString == "scala.this.Predef" && augmentString.toString == "augmentString" && size.toString == "size" => 
        val exactValue = stringVals.find(_.name == Some(id.toString)).map(_.exactValue)
        exactValue.map(v => Values(v.size)).getOrElse(Values.empty)
*/
      /// String stuff (TODO: there's a copy up at applyCond)
      case strFun @ Apply(Select(string, func), params) if string.tpe.widen <:< definitions.StringClass.tpe =>
        StringAttrs.stringFunc(string, func, params).right.getOrElse(Values.empty)
        
      case strFun @ Select(Apply(scala_augmentString, List(string)), func)
        if (scala_augmentString.toString endsWith "augmentString") =>
        
        StringAttrs.stringFunc(string, func).right.getOrElse(Values.empty)

      case strFun @ Apply(Select(Apply(scala_augmentString, List(string)), func), params)
        if (scala_augmentString.toString endsWith "augmentString") =>
        
        StringAttrs.stringFunc(string, func, params).right.getOrElse(Values.empty)

      /// Division by zero
      case pos @ Apply(Select(_, op), List(expr)) if (op == nme.DIV || op == nme.MOD) && (computeExpr(expr).contains(0)) => 
        unit.warning(pos.pos, "You will likely divide by zero here.")
        Values.empty

      // Range
      case Apply(Select(Apply(scala_Predef_intWrapper, List(Literal(Constant(low: Int)))), to_until), List(Literal(Constant(high: Int)))) 
        if (scala_Predef_intWrapper.toString endsWith "Wrapper") && (to_until.toString matches "to|until") =>
        
        val high2 = if(to_until.toString == "to") high else high-1
        new Values(Set((low, high2)), Set(), Set(), "", isSeq = true, high2-low)

      case Apply(Select(Apply(scala_Predef_intWrapper, List(expr1)), to_until), List(expr2))
        if (scala_Predef_intWrapper.toString endsWith "Wrapper") => 
        
        if(to_until.toString == "to") {
          (expr1, expr2) match {
            case (Literal(Constant(a)), Apply(Select(expr, nme.SUB), List(Literal(Constant(1))))) => 
              if(expr match {
                case Ident(id) => true
                case Select(Ident(id), size) if size.toString matches "size|length" => true //size value
                case Apply(Select(Ident(id), size), List()) if size.toString matches "size|length" => true //size getter
                case Select(Apply(implicitWrapper, List(Ident(id))), size) if size.toString matches "size|length" => true//wrapped size
                case _ => false
              }) unit.warning(treePosHolder.pos, "Use (low until high) instead of (low to high-1)")
            case _ =>
          }
        }
        
        if((to_until.toString matches "to|until") && computeExpr(expr1).isValue && computeExpr(expr2).isValue) {
          val (low, high) = (computeExpr(expr1).getValue, computeExpr(expr2).getValue + (if(to_until.toString == "to") 0 else -1))
          
          new Values(Set((low, high)), Set(), Set(), name = "", isSeq = true, actualSize = high-low)
        } else {
          Values.empty
        }

      case t @ Apply(TypeApply(genApply @ Select(_,_), _), genVals) if methodImplements(genApply.symbol, SeqLikeGenApply) && (!t.tpe.widen.toString.contains("collection.mutable.")) =>
        val values = genVals.map(v => computeExpr(v))
        if(values.forall(_.isValue)) new Values(Set(), values.map(_.getValue).toSet, Set(), "", isSeq = true, actualSize = values.size) else Values.empty.addActualSize(genVals.size)

      //Array isn't immutable, maybe later
      /*case Apply(Select(Select(scala, scala_Array), apply), genVals) if(scala_Array.toString == "Array") =>
        val values = genVals.map(v => computeExpr(v))
        if(values.forall(_.isValue)) new Values(Set(), values.map(_.getValue).toSet, Set(), "", isSeq = true, actualSize = values.size) else Values.empty.addActualSize(genVals.size)*/
      
      //TODO: is this for array or what?
      case Select(Apply(arrayOps @ Select(_, intArrayOps), List(expr)), op) if(arrayOps.toString == "scala.this.Predef.intArrayOps") => 
        computeExpr(expr).applyUnary(op)
      
      case Apply(TypeApply(t @ Select(expr, op), _), List(scala_math_Ordering_Int)) if scala_math_Ordering_Int.toString.endsWith("Int") => //.max .min
        computeExpr(t)

      case Apply(Select(scala_math_package, op), params) if scala_math_package.toString == "scala.math.`package`" =>
        op.toString match {
          case "abs"|"signum" if params.size == 1 => computeExpr(params.head).applyUnary(op)
          case "max"|"min"    if params.size == 2 => computeExpr(params(0))(op)(computeExpr(params(1)))
          case _ => Values.empty
        }

      case Apply(Select(scala_util_Random, nextInt), params) if nextInt.toString == "nextInt" => //if scala_util_Random.toString == "scala.util" => or rather the type
        if(params.size == 1) {
          val param = computeExpr(params.head)
          if(param.nonEmpty) {
            if(param.min <= 0) {
              unit.warning(treePosHolder.pos, "The parameter of this nextInt might be lower than 1 here.")
              Values.empty
            } else {
              Values(0, param.max-1)
            }
          } else {
            Values.empty
          }
        } else {
          //ADD: check what happens on +1, also if overflows are worthy of detection elsewhere :)
          //Values(Int.MinValue, Int.MaxValue)
          Values.empty
        }

      case Apply(TypeApply(Select(valName, op), _), List(scala_math_Numeric_IntIsIntegral)) if scala_math_Numeric_IntIsIntegral.toString == "math.this.Numeric.IntIsIntegral" && op.toString == "sum" =>
        computeExpr(valName).applyUnary(op)

      //List(Literal(Constant(1)), Literal(Constant(2)), Literal(Constant(3)), Literal(Constant(4)), Literal(Constant(0)), Literal(Constant(0))))
      case Select(expr, op) =>
        //println((expr, op, computeExpr(expr).applyUnary(op)))
        computeExpr(expr).applyUnary(op)

      case Apply(Select(expr1, op), List(expr2)) =>
        //println("BinaryOp: "+(op, expr1, expr2, (computeExpr(expr1))(op)(computeExpr(expr2))))
        (computeExpr(expr1))(op)(computeExpr(expr2))
      
      case Apply(Apply(TypeApply(Select(valName, map), List(_, _)), List(Function(List(ValDef(mods, paramName, _, EmptyTree)), expr))), _) if(map.toString == "map") => 
        //List(TypeApply(Select(Select(This(newTypeName("immutable")), scala.collection.immutable.List), newTermName("canBuildFrom")), List(TypeTree()))))
        
        val backupVals = vals.map(a=> a).withDefaultValue(Values.empty)
        val backupStrs = stringVals.clone
        val res = computeExpr(valName)
        vals += paramName.toString -> res
        //println(">        "+vals)
        val out = computeExpr(expr)
        vals = backupVals
        stringVals = backupStrs
        //println(">        "+out)
        out
      
      case If(condExpr, expr1, expr2) =>
        //TODO: if condExpr always/never holds, return that branch
        val backupVals = vals.map(a=> a).withDefaultValue(Values.empty)
        val backupStrs = stringVals.clone
        
        val e1 = computeExpr(expr1)
        vals = backupVals
        stringVals = backupStrs

        val e2 = computeExpr(expr2)
        vals = backupVals
        stringVals = backupStrs
        
        /*println(vals)
        vals = backupVals.map(a => (a._1, a._2.applyCond(condExpr)._1)).withDefaultValue(Values.empty)
        println("e1"+vals)
        val e1 = computeExpr(expr1)
        println("e1"+e1)
        stringVals = backupStrs

        vals = backupVals.map(a => (a._1, a._2.applyCond(condExpr)._2)).withDefaultValue(Values.empty)
        println("e2"+vals)
        val e2 = computeExpr(expr2)
        println("e2"+e1)*/
        
        vals = backupVals
        stringVals = backupStrs

        //if(e1.isValue && e2.isValue) new Values(values = e1.values ++ e2.values) else Values.empty
        if(expr1.tpe <:< definitions.NothingClass.tpe) {
          e2
        } else if(expr2.tpe <:< definitions.NothingClass.tpe) {
          e1
        } else if(!e1.isSeq && !e2.isSeq && e1.nonEmpty && e2.nonEmpty) {
          new Values(values = e1.values ++ e2.values, ranges = e1.ranges ++ e2.ranges)
        } else {
          Values.empty
        }

      case a => 
        //val raw = showRaw( a ); if(!exprs.contains(raw) && raw.size < 700 && raw.size > "EmptyTree".size)println("computeExpr: "+treePosHolder.toString+"\n"+raw); exprs += raw
        //for(Ident(id) <- a) if(stringVals.exists(_.name == Some(id.toString))) {println("id: "+id+"  "+showRaw( a )); }
        //println("computeExpr: "+showRaw( a ))
        Values.empty
    }
    //println(out+"  "+showRaw(tree))
    out
  }
  //val exprs = collection.mutable.HashSet[String]()
      
  // go immutable
  var vals = collection.mutable.HashMap[String, Values]().withDefaultValue(Values.empty)
  var stringVals = collection.mutable.HashSet[StringAttrs]()
  var treePosHolder: GTree = null //ugly hack to get position for a few warnings
  
  def forLoop(tree: GTree) {
    treePosHolder = tree
    //TODO: actually anything that takes (A <: (a Number) => _), this is awful
    val funcs = "foreach|map|filter(Not)?|exists|find|flatMap|forall|groupBy|count|((drop|take)While)|(min|max)By|partition|span"

    val backupVals = vals.map(a=> a).withDefaultValue(Values.empty)
    val backupStrs = stringVals.clone
    
    val (param, values, body, func) = tree match {
      case Apply(TypeApply(Select(collection, func), _), List(Function(List(ValDef(_, param, _, _)), body))) if (func.toString matches funcs) =>
        //println(showRaw(collection))
        val values = computeExpr(collection).addName(param.toString)
        //println(values)
        //if(values.isEmpty) {
            //println("not a collection I know("+tree.pos+"): "+showRaw(collection))
            //return
        //}
        
        (param.toString, values, body, func.toString)
      case _ => 
        //println("not a loop("+tree.pos+"): "+showRaw(tree))
        return
    }
    
    if(values.nonEmpty) {
      values.notSeq
      vals += param -> values
      
      if(!isUsed(body, param) && func != "foreach") unit.warning(tree.pos, "Iterator value is not used in the body.")

      traverseBlock(body)
    }
    vals = backupVals.withDefaultValue(Values.empty)
    stringVals = backupStrs
  }
  
  val visitedBlocks = collection.mutable.HashSet[GTree]()
  
  // Just something quick I hacked together for an actual bug
  //implicit def String2StringAttrs(s: String) = new StringAttrs(exactValue = Some(s))
  object StringAttrs {
    //TODO: when merging, save known chunks - .contains then partially works
    def empty: StringAttrs = new StringAttrs()
    
    def toStringAttrs(param: Tree): StringAttrs = {
      val intParam = computeExpr(param)
      
      if(intParam.isValue) new StringAttrs(Some(intParam.getValue.toString))
      else if(param.tpe.widen <:< definitions.IntClass.tpe) new StringAttrs(minLength = 1, trimmedMinLength = 1, maxLength = 11, trimmedMaxLength = 11)
      else if(param.tpe.widen <:< definitions.LongClass.tpe) new StringAttrs(minLength = 1, trimmedMinLength = 1, maxLength = 20, trimmedMaxLength = 20)
      //http://stackoverflow.com/questions/1701055/what-is-the-maximum-length-in-chars-needed-to-represent-any-double-value :)
      else if(param.tpe.widen <:< definitions.DoubleClass.tpe) new StringAttrs(minLength = 1, trimmedMinLength = 1, maxLength = 1079, trimmedMaxLength = 1079)
      else if(param.tpe.widen <:< definitions.FloatClass.tpe) new StringAttrs(minLength = 1, trimmedMinLength = 1, maxLength = 154, trimmedMaxLength = 154)
      else {
        //TODO: not sure if this is the right way, but <:< definitions.TraversableClass.tpe does not work directly
        if((param.tpe.baseClasses.exists(_.tpe =:= definitions.TraversableClass.tpe)) && !(param.tpe.widen <:< definitions.StringClass.tpe)) {
          // collections: minimal is Nil or Type()
          //TODO: Surely I can do moar... intParam.isSeq, etc
          val minLen = 3
          //println(Left(str + new StringAttrs(minLength = minLen, trimmedMinLength = minLen)))
          new StringAttrs(minLength = minLen, trimmedMinLength = minLen)
        } else {
          //TODO:discover moar
          //if(!(param.tpe.widen <:< definitions.StringClass.tpe) && !(param.tpe.widen <:< definitions.AnyClass.tpe))println(((str, param), (param.tpe, param.tpe.widen)))
        
          StringAttrs(param)
        }
      }
    }
    
    /// Tries to execute string functions and return either a String or Int representation
    def stringFunc(string: Tree, func: Name, params: List[Tree] = List[Tree]()): Either[StringAttrs, Values] = {
      val str = StringAttrs(string)
      lazy val intParam = if(params.size == 1 && params.head.tpe.widen <:< definitions.IntClass.tpe) computeExpr(params.head) else Values.empty
      lazy val intParams = if(params.forall(_.tpe.widen <:< definitions.IntClass.tpe)) params.map(computeExpr).toList else List() //option?
      lazy val stringParam = if(params.size == 1 && params.head.tpe.widen <:< definitions.StringClass.tpe) StringAttrs(params.head) else empty

      //println((string, func, params, str, intParam))
      //println(str.exactValue)
      //if(!(string.tpe.widen <:< definitions.StringClass.tpe))
      //if((string.tpe.widen <:< definitions.StringClass.tpe))println((string, func, params, str, intParam))
            
      // We can get some information, even if the string is unknown
      //if(str == StringAttrs.empty) {
      //  Left(empty)
      //} else 
      func.toString match {
        case "size"|"length" if params.size == 0 => 
          Right(
            str.exactValue
              .map(v => Values(v.size))
              .getOrElse(
                if(str.getMinLength == str.getMaxLength) 
                  Values(str.getMinLength)
                else if(str.getMinLength == 0 && str.getMaxLength == Int.MaxValue)
                  Values.empty
                else 
                  Values(str.getMinLength, str.getMaxLength)
              ).addCondition(_ >= 0)
          )
        case "toString" if params.size == 0 =>
          Left(toStringAttrs(string))
        case "$plus" if params.size == 1 =>
          Left(str + toStringAttrs(params.head))
        case "$times" if intParam.isValue =>
          Left(str * intParam.getValue)

        case f @ ("init"|"tail") => 
          if(str.exactValue.isDefined) {
            if(str.exactValue.get.isEmpty) {
              unit.warning(treePosHolder.pos, "Taking the "+f+" of an empty string.")
              Left(empty)
            } else {
              Left(new StringAttrs(str.exactValue.map(a => if(f=="init") a.init else a.tail)))
            }
          } else
            Left(new StringAttrs(minLength = math.max(str.minLength-1, 0), maxLength = if(str.maxLength != Int.MaxValue) math.max(str.maxLength-1, 0) else Int.MaxValue))

        case "capitalize" if params.size == 0 => if(str.exactValue.isDefined) Left(new StringAttrs(str.exactValue.map(_.capitalize))) else Left(str)
        case "distinct"   if params.size == 0 => if(str.exactValue.isDefined) Left(new StringAttrs(str.exactValue.map(_.distinct))) else Left(str.zeroMinLengths)
        case "reverse"    if params.size == 0 => if(str.exactValue.isDefined) Left(new StringAttrs(str.exactValue.map(_.reverse))) else Left(str)
        case "count"      if params.size == 1 => 
          val out = Values.empty.addCondition(_ >= 0)
          Right(if(str.getMaxLength != Int.MaxValue) out.addCondition(_ < str.getMaxLength) else out)
        case "filter"     if params.size == 1 => 
          println(Left(str.zeroMinLengths))
          Left(str.removeExactValue.zeroMinLengths)
        
        //These come in (Char/String) versions
        case "stringPrefix" if params.size == 0 && str.exactValue.isDefined => Left(new StringAttrs(str.exactValue.map(_.stringPrefix)))
        case "stripLineEnd" if params.size == 0 && str.exactValue.isDefined => Left(new StringAttrs(str.exactValue.map(_.stripLineEnd)))
        case "stripMargin"  if params.size == 0 && str.exactValue.isDefined => Left(new StringAttrs(str.exactValue.map(_.stripMargin)))
        
        case "toUpperCase" => if(str.exactValue.isDefined) Left(new StringAttrs(str.exactValue.map(_.toUpperCase))) else Left(str)
        case "toLowerCase" => if(str.exactValue.isDefined) Left(new StringAttrs(str.exactValue.map(_.toLowerCase))) else Left(str)
        case "trim" => 
          val newExactValue = str.exactValue.map(_.trim)
          val newMinLength = str.exactValue.map(_.trim.size).getOrElse(str.getTrimmedMinLength)
          val newMaxLength = str.exactValue.map(_.trim.size).getOrElse(str.getTrimmedMaxLength)
          Left(new StringAttrs(
            exactValue = newExactValue, 
            minLength = newMinLength, 
            trimmedMinLength = newMinLength, 
            maxLength = newMaxLength, 
            trimmedMaxLength = newMaxLength))
        case "nonEmpty"|"isEmpty" => 
          if(str.alwaysNonEmpty) unit.warning(treePosHolder.pos, "This string will never be empty.")
          if(str.alwaysIsEmpty) unit.warning(treePosHolder.pos, "This string will always be empty.")
          Left(empty)
        case "toInt" if str.exactValue.isDefined =>
          try {
            Right(Values(str.exactValue.get.toInt))
          } catch {
            case e: Exception =>
              unit.warning(treePosHolder.pos, "This String toInt conversion will likely fail.")
              Left(empty)
          }
        //str.func(Int)
        case f @ ("charAt"|"codePointAt"|"codePointBefore"|"substring"
                 |"apply"|"drop"|"take"|"dropRight"|"takeRight") if intParam.isValue =>
          val param = intParam.getValue
          lazy val string = str.exactValue.get //lazy to avoid None.get... didn't use monadic, because I was lazy

          //println((string, param))
          
          //TODO use reflection, dummy :)
          try f match {
            case "charAt"|"apply" => 
              if(str.exactValue.isDefined) { string.charAt(param); Left(empty) } else if(param < 0) throw new IndexOutOfBoundsException else Left(empty)
            case "codePointAt" => 
              if(str.exactValue.isDefined) Right(Values(string.codePointAt(param))) else if(param < 0) throw new IndexOutOfBoundsException else Left(empty)
            case "codePointBefore" => 
              if(str.exactValue.isDefined) Right(Values(string.codePointBefore(param))) else if(param < 1) throw new IndexOutOfBoundsException else Left(empty)
            case "substring" => 
              if(str.exactValue.isDefined) Left(new StringAttrs(Some(string.substring(param)))) else if(param < 0) throw new IndexOutOfBoundsException else Left(empty)
            case "drop" => 
              if(str.exactValue.isDefined) 
                Left(new StringAttrs(Some(string.drop(param)))) 
              else 
                Left(new StringAttrs(minLength = math.max(str.minLength-param, 0), maxLength = if(str.maxLength != Int.MaxValue) math.max(str.maxLength-param, 0) else Int.MaxValue))
            case "take" => 
              if(str.exactValue.isDefined) 
                Left(new StringAttrs(Some(string.take(param))))
              else
                Left(new StringAttrs(minLength = math.min(param, str.minLength), maxLength = math.max(param, 0)))
            case "dropRight" => 
              if(str.exactValue.isDefined)
                Left(new StringAttrs(Some(string.dropRight(param))))
              else 
                Left(new StringAttrs(minLength = math.max(str.minLength-param, 0), maxLength = if(str.maxLength != Int.MaxValue) math.max(str.maxLength-param, 0) else Int.MaxValue))
            case "takeRight" => 
              if(str.exactValue.isDefined) 
                Left(new StringAttrs(Some(string.takeRight(param))))
              else 
                Left(new StringAttrs(minLength = math.min(param, str.minLength), maxLength = math.max(param, 0)))
            case a => Left(empty)
          } catch {
            case e: IndexOutOfBoundsException =>
              unit.warning(params.head.pos, "This index will likely cause an IndexOutOfBoundsException.")
              Left(empty)
            case e: Exception =>
              Left(empty)
          }
          
        //str.func(String)
        case f @ ("contains"|"startsWith"|"endsWith") if str.exactValue.isDefined && stringParam.exactValue.isDefined =>
          val (string, param) = (str.exactValue.get, stringParam.exactValue.get)
          
          //TODO: calculate with lengths - (maxLength == 5) will never contain (minLength == 7)
          
          f match {
            case "contains"   => unit.warning(params.head.pos, "This contains will always return "+string.contains(param)+".")
            case "startsWith" => unit.warning(params.head.pos, "This startsWith will always return "+string.startsWith(param)+".")
            case "endsWith"   => unit.warning(params.head.pos, "This endsWith will always return "+string.endsWith(param)+".")
            case "compare"    => unit.warning(params.head.pos, "This compare will always return "+string.compare(param)+".")
            case "compareTo"  => unit.warning(params.head.pos, "This compareTo will always return "+string.compareTo(param)+".")
            case _ =>
          }
          Left(empty)
          
        //str.func(X, X)
        case f @ ("substring"|"codePointCount") if intParams.size == 2 && intParams.forall(_.isValue) =>
          lazy val string = str.exactValue.get //lazy to avoid None.get... didn't use monadic, because I was lazy
          val param = intParams.map(_.getValue)

          //println((string, param))
          
          try f match {
            case "substring" =>
              if(str.exactValue.isDefined) 
                Left(new StringAttrs(Some(string.substring(param(0), param(1)))))
              else if(param(0) < 0 || param(1) < param(0) || param(0) >= str.getMaxLength || param(1) >= str.getMaxLength)
                throw new IndexOutOfBoundsException
              else if(param(0) == param(1))
                Left(new StringAttrs(Some("")))
              else
                Left(empty)
            case "codePointCount" =>
              if(str.exactValue.isDefined) 
                Right(Values(string.codePointCount(param(0), param(1))))
              else if(param(0) < 0 || param(1) < param(0) || param(0) >= str.getMaxLength || param(1) >= str.getMaxLength)
                throw new IndexOutOfBoundsException
              else
                Left(empty)
            case a => Left(empty)
          } catch {
            case e: IndexOutOfBoundsException =>
              unit.warning(params.head.pos, "This index will likely cause an IndexOutOfBoundsException.")
              Left(empty)
            case e: Exception =>
              Left(empty)
          }        
        
        case _ =>
          //if(str.exactValue.isDefined)println((str, func, params))
          Left(empty)
      }
    }

    
    def apply(tree: Tree): StringAttrs = {
      def traverse(tree: Tree): StringAttrs = tree match {
        case Literal(Constant(null)) => new StringAttrs(exactValue = Some("null"))
        case Literal(Constant(c)) => 
          if(stringVals.filter(s => s.name.isDefined && !(vars contains s.name.get)).exists(_.exactValue == Some(c.toString))) {
            //unit.warning(tree.pos, "You have defined that string as a val already, maybe use that?")
          }

          new StringAttrs(exactValue = Some(c.toString))
          
        case Ident(name) => stringVals.find(_.name.exists(_ == name.toString)).getOrElse(empty)
        case If(cond, expr1, expr2) =>
          val (e1, e2) = (traverse(expr1), traverse(expr2))
          
          new StringAttrs(
            minLength = math.min(e1.getMinLength, e2.getMinLength), 
            trimmedMinLength = math.min(e1.getTrimmedMinLength, e2.getTrimmedMinLength),
            maxLength = math.max(e1.getMaxLength, e2.getMaxLength), 
            trimmedMaxLength = math.max(e1.getTrimmedMaxLength, e2.getTrimmedMaxLength))

        case Apply(augmentString, List(expr)) if(augmentString.toString == "scala.this.Predef.augmentString") =>
          StringAttrs(expr)
          
        /// Pass on functions on strings
        //TODO: maybe check if some return string and can be computed
        case Apply(Select(str, func), params) => 
          stringFunc(str, func, params).left.getOrElse(empty)

        case Select(Apply(scala_augmentString, List(string)), func) if (scala_augmentString.toString endsWith "augmentString") =>
          stringFunc(string, func).left.getOrElse(empty)
            
        case a => 
          //println(showRaw(a))
          empty
      }
      
      val a = traverse(tree)
      //println("tree: "+ a)
      a
    }
  }
  class StringAttrs(
      val exactValue: Option[String] = None,
      val name: Option[String] = None, //Move to outer map?
      private val minLength: Int = 0,
      private val trimmedMinLength: Int = 0,
      private val maxLength: Int = Int.MaxValue,
      private val trimmedMaxLength: Int = Int.MaxValue) {
    
    def addName(name: String): StringAttrs = new StringAttrs(exactValue, Some(name), getMinLength, trimmedMinLength, getMaxLength, trimmedMaxLength)
    def removeExactValue: StringAttrs = new StringAttrs(None, name, getMinLength, trimmedMinLength, getMaxLength, trimmedMaxLength)
    def zeroMinLengths: StringAttrs = new StringAttrs(exactValue, name, 0, 0, getMaxLength, trimmedMaxLength)
    
    def alwaysIsEmpty: Boolean = getMaxLength == 0
    def alwaysNonEmpty: Boolean = getMinLength > 0
    
    def getMinLength: Int = exactValue.map(_.size).getOrElse(minLength)
    def getMaxLength: Int = exactValue.map(_.size).getOrElse(maxLength)
    def getTrimmedMinLength: Int = exactValue.map(_.trim.size).getOrElse(trimmedMinLength)
    def getTrimmedMaxLength: Int = exactValue.map(_.trim.size).getOrElse(trimmedMaxLength)
    
    def +(s: String): StringAttrs = 
      new StringAttrs(
        exactValue = if(this.exactValue.isDefined) Some(this.exactValue.get + s) else None,
        minLength = this.getMinLength + s.length,
        trimmedMinLength = this.getTrimmedMinLength + s.trim.length, //ADD: can be made more exact
        maxLength = if(this.maxLength == Int.MaxValue) Int.MaxValue else this.getMaxLength + s.length,
        trimmedMaxLength = if(this.getTrimmedMaxLength == Int.MaxValue) Int.MaxValue else this.getTrimmedMaxLength + s.trim.length 
      )
    def +(s: StringAttrs): StringAttrs = 
      new StringAttrs(
        exactValue = if(this.exactValue.isDefined && s.exactValue.isDefined) Some(this.exactValue.get + s.exactValue.get) else None,
        minLength = this.getMinLength + s.getMinLength,
        trimmedMinLength = this.getTrimmedMinLength + s.getTrimmedMinLength, //ADD: can be made more exact
        maxLength = if(this.getMaxLength == Int.MaxValue || s.getMaxLength == Int.MaxValue) Int.MaxValue else this.getMaxLength + s.getMaxLength,
        trimmedMaxLength = if(this.getTrimmedMaxLength == Int.MaxValue || s.getTrimmedMaxLength == Int.MaxValue) Int.MaxValue else this.getTrimmedMaxLength + s.getTrimmedMaxLength
      )
    def *(n: Int): StringAttrs = 
      if(n <= 0) {
        unit.warning(treePosHolder.pos, "Multiplying a string with a value <= 0 will always result in an empty string.")
        new StringAttrs(Some(""))
      } else {
        new StringAttrs(
          exactValue = if(this.exactValue.isDefined) Some(this.exactValue.get*n) else None,
          minLength = this.getMinLength*n,
          trimmedMinLength = this.getTrimmedMinLength*n, //ADD: can be made more exact
          maxLength = if(this.getMaxLength == Int.MaxValue) Int.MaxValue else this.getMaxLength*n,
          trimmedMaxLength = if(this.getTrimmedMaxLength == Int.MaxValue) Int.MaxValue else this.getTrimmedMaxLength*n)
      }
    
    override def hashCode: Int = /*if(exactValue.isDefined) exactValue.hashCode else */exactValue.hashCode + name.hashCode + minLength + trimmedMinLength + maxLength + trimmedMaxLength
    override def equals(that: Any): Boolean = that match {
      case s: StringAttrs => (this.exactValue.isDefined && s.exactValue.isDefined && this.exactValue.get == s.exactValue.get)
      case s: String => exactValue.exists(_ == s)
      case _ => false
    }
    
    override def toString: String = (exactValue, name, getMinLength, getTrimmedMinLength, getMaxLength, getTrimmedMaxLength).toString
  }
 
  var vars = collection.mutable.HashSet[String]()
  var labels = collection.mutable.HashMap[String, GTree]()        
  def discardVars(tree: GTree, force: String*) {
    for(v <- vars; if isAssigned(tree, v) || (force contains v)) {
      vals(v) = Values.empty
      stringVals = stringVals.filter(v => v.name.isDefined && !(vars contains v.name.get))
      //println("discard: "+(vals))
    }
  }

  def traverseBlock(tree: GTree) {
    val backupVals = vals.map(a=> a).withDefaultValue(Values.empty)
    val backupVars = vars.map(a=> a)
    val backupStrs = stringVals.clone
    
    traverse(tree)
    
    vals = backupVals.withDefaultValue(Values.empty)
    vars = backupVars
    stringVals = backupStrs
  }
  def traverse(tree: GTree) {
    if(visitedBlocks(tree)) return else visitedBlocks += tree
    treePosHolder = tree
    tree match {
      /// Very hacky support for some var interpretion
      /*case ValDef(m: Modifiers, varName, _, value) if(m.hasFlag(MUTABLE)) =>
        vars += varName.toString
        vals(varName.toString) = computeExpr(value)
        visitedBlocks += tree
        //println("assign: "+(vals))*/
      case Assign(varName, value) if vars contains varName.toString =>
        vals(varName.toString) = computeExpr(value)
        //println("reassign: "+(vals))
        visitedBlocks += tree
      
      case LabelDef(label, List(), If(cond, body, unit)) if {
        //discardVars(cond, (vars & getUsed(cond)).toSeq:_*)
        //discardVars(body)
        discardVars(tree, (vars & getUsed(tree)).toSeq:_*)
        labels += label.toString -> tree
        visitedBlocks += tree        
        false
      } => //Fallthrough
      case Apply(label, List()) if (labels contains label.toString) && {
        // TODO: check if there is an infinite...
        // vals.filter(a => vars contains a._1).map(a =>println(a._1, a._2.applyCond(labels(label.toString))._2))
        
        discardVars(labels(label.toString))//, (vars & getUsed(labels(label.toString))).toSeq:_*)

        labels -= label.toString
        false
      } => //Fallthrough
      case e if { //throw away assigns inside other blocks
        discardVars(e)
        false
      } => //Fallthrough

      case forloop @ Apply(TypeApply(Select(collection, foreach_map), _), List(Function(List(ValDef(_, _, _, _)), _))) =>
        forLoop(forloop)
      
      /// Assertions checks
      case Apply(Select(scala_Predef, assertion), List(condExpr)) 
        if (scala_Predef.tpe.widen <:< definitions.PredefModule.tpe) && (assertion.toString matches "assert|assume|require") => 
        
        // we can apply these conditions to vals - if they don't hold, it'll throw an exception anyway
        // and they'll reset at the end of the current block
        vals = vals.map(a => (a._1, a._2.applyCond(condExpr)._1)).withDefaultValue(Values.empty)
      
      /// String checks
      //case s @ Literal(Constant(str: String)) if stringVals.filter(s => s.name.isDefined && !(vars contains s.name.get)).find(_.exactValue == Some(str)).isDefined =>
        //unit.warning(s.pos, "You have defined that string as a val already, maybe use that?")
        //visitedBlocks += s

      case ValDef(m: Modifiers, valName, _, s @ Literal(Constant(str: String))) if(!m.hasFlag(MUTABLE) && !m.hasFlag(FINAL)) =>
        //if(stringVals.filter(s => s.name.isDefined && !(vars contains s.name.get)).exists(_.exactValue == Some(str))) unit.warning(s.pos, "You have defined that string as a val already, maybe use that?")
        //stringVals += str
        //visitedBlocks += s
        
        val str2 = StringAttrs(s).addName(valName.toString)
        //println("str2: "+str2)
        if(str2.exactValue.isDefined || str2.getMinLength > 0) {
          stringVals += str2
        }
        //println("stringVals2: "+stringVals)

      case ValDef(m: Modifiers, valName, _, Literal(Constant(a: Int))) if(!m.hasFlag(MUTABLE)) =>
        val valNameStr = valName.toString.trim
        vals += valNameStr -> Values(a, valNameStr)
        //println(vals(valName.toString))

      case v@ValDef(m: Modifiers, valName, _, expr) => //if !m.hasFlag(MUTABLE) /*&& !m.hasFlag(LAZY)) && !computeExpr(expr).isEmpty*/ => //&& computeExpr(expr).isValue =>
        //ADD: aliasing... val a = i, where i is an iterator, then 1/i-a is divbyzero
        //ADD: isSeq and actualSize

        if(expr.tpe.widen <:< definitions.StringClass.tpe) {
          val str = StringAttrs(expr).addName(valName.toString)
          //println("str1: "+str)
          if(str.exactValue.isDefined || str.getMinLength > 0) {
            stringVals += str
            visitedBlocks += expr
          }
          //println("stringVals1: "+stringVals)
        }

        val valNameStr = valName.toString
        val res = computeExpr(expr).addName(valNameStr)
        vals += valNameStr -> res
        if(m.hasFlag(MUTABLE)) vars += valNameStr
        
       
        //println("newVal: "+computeExpr(expr).addName(valNameStr))
        //println("newVal: "+vals(valName.toString))
        //println(showRaw(expr))
        
        expr match {
          case e => //Block(_, _) | If(_,_,_) =>
            //println(expr)
            val backupVals = vals.map(a=> a).withDefaultValue(Values.empty)
            val backupStrs = stringVals.clone
            traverse(expr)
            vals = backupVals.withDefaultValue(Values.empty)
            stringVals = backupStrs
          //case _ =>
        }
      
      case Match(pat, cases) if pat.tpe.toString != "Any @unchecked" && cases.size >= 2 =>
        for(c <- cases) {
          val backupVals = vals.map(a=> a).withDefaultValue(Values.empty)
          val backupStrs = stringVals.clone
          //TODO: c.pat can override some variables
          traverse(c.body)
          vals = backupVals.withDefaultValue(Values.empty)
          stringVals = backupStrs
        }
      
      case If(condExpr, t, f) => //TODO: moved to computeExpr?
        val backupVals = vals.map(a=> a).withDefaultValue(Values.empty)
        val backupStrs = stringVals.clone
        
        //println(vals)
        vals = backupVals.map(a => (a._1, a._2.applyCond(condExpr)._1)).withDefaultValue(Values.empty)
        //println(vals)
        //ADD: if always true, or always false pass the return value, e.g. val a = 1; val b = if(a == 1) 5 else 4
        t.foreach(traverse)

        vals = backupVals.withDefaultValue(Values.empty)
        vals = backupVals.map(a => (a._1, a._2.applyCond(condExpr)._2)).withDefaultValue(Values.empty)
        f.foreach(traverse)
        
        vals = backupVals.withDefaultValue(Values.empty)
        stringVals = backupStrs
        //println(vals)

      //case pos @ Apply(Select(Ident(seq), apply), List(indexExpr)) 
      case pos @ Apply(Select(seq, apply), List(indexExpr)) if methodImplements(pos.symbol, SeqLikeApply) =>
        //println(seq.toString)
        //println("indexExpr: "+computeExpr(indexExpr))
        //println(showRaw(indexExpr))
        if(vals.contains(seq.toString) && vals(seq.toString).actualSize != -1 && (computeExpr(indexExpr).existsGreater(vals(seq.toString).actualSize-1))) {
          unit.warning(pos.pos, "You will likely use a too large index for a collection here.")
        }
        if(computeExpr(indexExpr).existsLower(0)) {
          unit.warning(pos.pos, "You will likely use a negative index for a collection here.")
        }
      
      case DefDef(_, _, _, params, _, block @ Block(b, last)) => 
        val backupVals = vals.map(a=> a).withDefaultValue(Values.empty)
        val backupStrs = stringVals.clone
        
        val paramNames = params.flatten.map(_.name.toString)
        
        vals = vals.filterNot(paramNames contains _)
        
        if(block.children.isEmpty)
          traverse(block)
        else
          block.children.foreach(traverse)
        
        val returnVal = last match {
          case Return(ret) => 
            unit.warning(last.pos, "Scala has implicit return, you don't need a return statement at the end of a method")
            ret
          case a => 
            a
        }
        
        if(returnCount(block) == 0 && computeExpr(returnVal).isValue) {
          unit.warning(last.pos, "This method always returns the same value: "+computeExpr(returnVal).getValue)
        }

        vals = backupVals.withDefaultValue(Values.empty)
        stringVals = backupStrs

      /// Invalid regex
      case Apply(java_util_regex_Pattern_compile, List(regExpr)) if java_util_regex_Pattern_compile.toString == "java.util.regex.Pattern.compile" =>
        treePosHolder = regExpr
        StringAttrs(regExpr).exactValue.foreach(checkRegex)
      
      case Apply(Select(str, func), List(regExpr)) if (str.tpe.widen <:< definitions.StringClass.tpe) && (func.toString matches "matches|split") =>
        treePosHolder = regExpr
        StringAttrs(regExpr).exactValue.foreach(checkRegex)
        
      case Apply(Select(str, func), List(regExpr, str2)) if (str.tpe.widen <:< definitions.StringClass.tpe) && (func.toString matches "replace(All|First)") =>
        treePosHolder = regExpr
        StringAttrs(regExpr).exactValue.foreach(checkRegex)

      case Select(Apply(scala_Predef_augmentString, List(regExpr)), r)
        if(scala_Predef_augmentString.toString.endsWith(".augmentString") && r.toString == "r") =>
        treePosHolder = regExpr
                
        StringAttrs(regExpr).exactValue.foreach(checkRegex)

      /// Option checks
      /// Checks for Option[Traversable[A]].size, which is probably a bug (use .isDefined instead)
      case t @ Select(Apply(option2Iterable, List(opt)), size)
        if (option2Iterable.toString contains "Option.option2Iterable") && size.toString == "size" 
        && opt.tpe.widen.typeArgs.exists(tp => tp.widen <:< definitions.StringClass.tpe || tp.widen.baseClasses.exists(_.tpe =:= definitions.TraversableClass.tpe)) =>

        unit.warning(t.pos, "Did you mean to take the size of the collection inside the Option?")
        
      //ADD: Generalize... move to applyCond completely, make it less hacky
      case t @ Apply(Select(Select(Apply(option2Iterable, List(opt)), size), op), List(expr))
        if (option2Iterable.toString contains "Option.option2Iterable") && size.toString == "size" && t.tpe.widen <:< definitions.BooleanClass.tpe =>

        val backupVals = vals.map(a=> a).withDefaultValue(Values.empty)
        val backupStrs = stringVals.clone

        val valName = "__foobar__" //shoot me :P
        
        vals += valName -> (new Values(values = Set(0,1)))
        
        val cond = Apply(Select(Ident(newTermName(valName)), op), List(expr))
        cond.pos = t.pos
        
        vals(valName).applyCond(cond)
        
        vals = backupVals.withDefaultValue(Values.empty)
        stringVals = backupStrs

      case b @ Block(stmts, ret) => 
        //println("block: "+b)
        val backupVals = vals.map(a=> a).withDefaultValue(Values.empty)
        val backupStrs = stringVals.clone
        b.children.foreach(traverse)
        
        /// Try to use vars - TODO: is probably buggy
        /*val block = stmts :+ ret
        val vars = collection.mutable.HashSet[String]()
        block foreach {
          case ValDef(m: Modifiers, varName, _, value) if(m.hasFlag(MUTABLE)) =>
            vars += varName.toString
            vals(varName.toString) = computeExpr(value)
            println("assign: "+(vals))
          case Assign(varName, value) if vars contains varName.toString =>
            vals(varName.toString) = computeExpr(value)
            println("reassign: "+(vals))
          case e => //throw away assigns inside other blocks
            for(v <- vars; if isAssigned(e, v)) {
              vals(v) = Values.empty
              println("discard: "+(vals))
            }
        }
        */
        vals = backupVals.withDefaultValue(Values.empty)
        stringVals = backupStrs

      /// Pass on expressions
      case pos @ Apply(Select(_, op), List(expr)) =>
        //if (op == nme.DIV || op == nme.MOD) && (computeExpr(expr).contains(0)) => 
        
        computeExpr(pos)
        tree.children.foreach(traverse)

      case _ =>
        //if(vals.nonEmpty)println("in: "+showRaw(tree))
        //if(vals.nonEmpty)println(">   "+vals);
        //if(showRaw(tree).startsWith("Literal") || showRaw(tree).startsWith("Constant"))println("in: "+showRaw(tree))
        tree.children.foreach(traverse)
    }
  }
}
