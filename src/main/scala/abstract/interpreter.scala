// This file contains the definitions of the notJS state transition
// rules. See the notJS semantics document Section 3.4 for the
// specification.

package notjs.abstracted.interpreter

import notjs.util._
import notjs.syntax._
import notjs.translator._
import notjs.abstracted.init._
import notjs.abstracted.traces._
import notjs.abstracted.domains._
import notjs.abstracted.domains.Store.AddrNotFound 
import notjs.abstracted.domains.AddressSpace._
import notjs.abstracted.helpers.Errors._
import notjs.abstracted.helpers.Helpers._
import notjs.abstracted.helpers.Filters._
import notjs.abstracted.helpers.Fields.code
import notjs.abstracted.eval._
import notjs.abstracted.clients.PDG._
import notjs.abstracted.clients.graph._

import scala.collection.{ Seq ⇒ Sequence, SortedSet }
import scala.collection.mutable.{ HashMap, HashSet, PriorityQueue, Map ⇒ MMap }
import java.io._

//——————————————————————————————————————————————————————————————————————————————
// Abstract interpreter entry point

object notJS {

  def main( args:Array[String] ) {
    // encapsulate everything in runner so that we can run an analysis
    // multiple times in a row if we want to
    runner(args)
  }

  // container to isolate mutable state
  object Mutable {
    // command-line flags
    var lightGC = false     // use lightweight garbage collection
    var fullGC = false      // use full garbage collection
    var pruneStore = false  // prune the store for function calls
    var dangle = false      // leave out unimplemented init state
                            // as dangling if true, by default, all
                            // references to them are removed   

    // debugging command-line flags
    var testing = false     // post-fixpoint, takes an extra pass to collect
                            // print data (but not print them to stdout)
                            // used by the sbt testing framework
    var print = false       // process print statement after analysis is done
    var catchExc = false    // suppress Scala exceptions raised by analysis

    var genSig = false      // generates a control flow graph
    var genVisuals = false  // generate a visualization tool of the annPDG

    // empty PDG, filled during the post fix point of the analysis
    val pdg = PDGInterface()

    // for debugging (but not a command-line flag)
    var inPostFixpoint = false

    // map entries in PrunedStoreToo to the corresponding Merge traces
    // (explained further in helpers.scala applyClo)
    val prunedInfo = HashMap[Trace,(Trace, Var, Addresses)]()

    // maps print ids to their values in post fixpoint run
    // enabled for testing
    // each print id is mapped to a set of bvalues
    // because multiple traces can reach the same print statement
    // with different bvalues
    val outputMap = MMap[Int, Set[BValue]]().withDefaultValue(Set[BValue]())
    var lastSavedTime = System.currentTimeMillis()

    // get the elapsed time in seconds since the last time 
    // this method was called
    def getTimeSincePrevCall() = {
      val curTime = System.currentTimeMillis()
      val diff = (curTime - lastSavedTime)
      lastSavedTime = curTime
      (diff/1000.0)
    }

    // reset mutable state to initial conditions
    def clear {
      Mutable.lightGC = false
      Mutable.fullGC = false 
      Mutable.pruneStore = false
      Mutable.dangle = false
      Mutable.testing = false
      Mutable.print = false
      Mutable.catchExc = false
      Mutable.genSig = false
      Mutable.genVisuals = false
      Mutable.inPostFixpoint = false
      Mutable.prunedInfo.clear()
      Mutable.outputMap.clear()
      Mutable.pdg.clear
    }
  }

  // this performs the actual analysis
  def runner( args:Array[String] ): MMap[Int, Set[BValue]] = {
    // clear mutable data structures so that we start with a clean slate
    Mutable.clear
    PruneScratch.clear
    PruneStoreToo.clear

    // worklist for fixpoint computation
    val work = new PriorityQueue[(Int,Trace)]()(OrderWorklist)

    // read command-line flags
    val opts = args.toSet
    Mutable.lightGC    = opts("--lightgc")
    Mutable.fullGC     = opts("--fullgc")
    Mutable.pruneStore = opts("--prune")
    Mutable.testing    = opts("--testing")
    Mutable.print      = opts("--print")
    Mutable.catchExc   = opts("--catchexc")

    Mutable.dangle     = opts("--dangle")
    Mutable.genSig     = opts("--genSig")
    Mutable.genVisuals = opts("--genVisuals")

    // make sure the options are all sanitized
    val validOptionSet = 
      Set("--lightgc",
          "--fullgc",
          "--prune",
          "--testing",
          "--print",
          "--catchexc",
          "--dangle",
          "--genSig",
          "--genVisuals",
          "--time",
          "--memory")

    def validOption(op: String): Boolean =
      op.startsWith("--trace=") || op.endsWith(".js") || validOptionSet(op)
    
    val invalidOpts = opts.filterNot(validOption)
    assert(invalidOpts.isEmpty,
           "Unrecognized options: " + invalidOpts.mkString(", "))

    assert(!Mutable.lightGC || !Mutable.fullGC,
           "Cannot do lightweight gc as well as full gc at the same time")

    // get initial trace from command-line option; the default is a
    // flow-sensitive context-insensitive analysis
    val initτ = opts.find(_.startsWith("--trace=")) match {
      case Some(str) ⇒ optionToTrace(str)
      case None ⇒ FSCI(0)
    } 

    // parse and translate program to get a notJS AST
    val ast = readAST( args(0) )

    // memoization table for fixpoint computation
    val memo = HashMap[Trace, State]()

    // do the fixpoint computation
    try {
      // initialize worklist
      val initς = Init.initState( ast, initτ )
      process( initς ) foreach ( ς ⇒ {
        memo(ς.τ) = memo get ς.τ match {
          case None ⇒ ς
          case Some(ς1) ⇒ ς1 ⊔ ς
        }
        work += ((ς.order, ς.τ))
      })

      // get the start time for the fixpoint computation
      val startTime = System.currentTimeMillis()
      println("Time before analysis: " + notJS.Mutable.getTimeSincePrevCall())
      // calculate widened fixpoint
      do {
        while ( work.nonEmpty ) {
          val (_, τ) = work.dequeue
          
          // eliminate duplicate entries in the worklist
          while ( work.nonEmpty && work.head._2 == τ ) work.dequeue

          process( memo(τ) ) foreach (
            (ς) ⇒ memo get ς.τ match {
              case None ⇒ // first time at this point
                memo(ς.τ) = ς
                work += ((ς.order, ς.τ))

              case Some(ς1) ⇒ // we've been here before
                val merged = ς1 ⊔ ς
                if ( ς1 != merged ) { // new information
                  memo(ς.τ) = merged
                  work += ((merged.order, merged.τ))
                }
            })
        }

        // for if we're doing store pruning (otherwise prunedInfo is
        // empty): make sure that any new unreachable information from
        // pruned calls is propagated past the call (explained further
        // in helpers.scala applyClo)
        Mutable.prunedInfo foreach {
          case (mτ, (pτ, x, as)) ⇒
            val (pσ, pß) = PruneStoreToo(pτ)
            memo get mτ match {
              case Some(ς1) ⇒
                // if the call's left-hand side variable (the
                // parameter 'x' above) is either a Scratch or a PVar
                // with a strong address, the RetK will write over its
                // current value. however, the pruned information in
                // PruneStoreToo contains the old values, and when
                // those old values are joined back in we'll lose
                // precision. to prevent this, we replace the current
                // value of x in PruneStoreToo with ⊥
                val (new_σ, new_ß) = 
                  x match {
                    case _:PVar if pσ a2v_contains as ⇒ 
                      ((pσ + (as → BValue.⊥)) ⊔ ς1.σ, pß ⊔ ς1.ß)
                    case sc:Scratch ⇒ 
                      (pσ ⊔ ς1.σ, (pß(sc) = BValue.⊥) ⊔ ς1.ß)
                    case _ ⇒ 
                      (pσ ⊔ ς1.σ, pß ⊔ ς1.ß)
                  }
                val merged = State(ς1.t, ς1.ρ, new_σ, new_ß, ς1.κs, ς1.τ)
                if ( merged != ς1 ) {
                  memo(mτ) = merged
                  work += ((merged.order, merged.τ))
                }

              case None ⇒ ()
            }
        }
        Mutable.prunedInfo.clear()
      }
      while ( work.nonEmpty )

      // print out the elapsed time
      if ( opts("--time") ) {
        val elapsedTime = System.currentTimeMillis() - startTime
        println("Elapsed time = " + elapsedTime/1000.0 + " secs")
      }

      // print out the memory used at this point
      if (opts("--memory")) {
        System.gc()
        val memUsed = (Runtime.getRuntime().totalMemory() -
                      Runtime.getRuntime().freeMemory()) /
                      (1024L * 1024L)
        println("Memory in use after the analysis: " + memUsed + " MB")
      }

      // make another pass to print things out
      if ( Mutable.testing || 
           Mutable.print ||
           Mutable.genSig ) {
        Mutable.inPostFixpoint = true
        process(initς)
        memo.values.foreach(process(_))
      }
      if ( Mutable.print ) {
        println( Mutable.outputMap.toList.sortWith(
          (a, b) ⇒ a._1 < b._1).map(
            t ⇒ t._1 + ": " + t._2.mkString(", ") 
          ).mkString("\n") 
        )
      }
      if ( Mutable.genSig ) {
        val (annotatedPDG, signature) = Mutable.pdg.computeSignature()

        if ( Mutable.genVisuals ) {
          val sliceVisualizerName = new File(args(0)).getName + ".html"
          Mutable.pdg.writeSliceVisualizer(ast, annotatedPDG, sliceVisualizerName)
        }
      }
      Mutable.outputMap
    }
    catch { // analysis generated an exception
      case _ if Mutable.catchExc ⇒ { // don't print exception details
        if ( Mutable.print ) 
          println("Abstract interpreter threw exception")
        Mutable.outputMap
      }

      case e: Throwable ⇒ { // do print exception details
        println("Exception occured: " + e.getMessage() + "\n" + 
                e.getStackTraceString)
        Mutable.outputMap
      }
    } 
  }

  // compute the starting trace given the command-line option
  def optionToTrace(str: String): Trace = {
    val fs = "--trace=fs"
    val stack = """--trace=stack-(\d+)-(\d+)""".r
    val acyclic = """--trace=acyclic-(\d+)""".r
    val obj = """--trace=obj-(\d+)-(\d+)""".r
    val sig = """--trace=sig-(\d+)-(\d+)""".r
    val cxo = """--trace=cxo-(\d+)-(\d+)-(\d+)-(\d+)""".r
    val cno = """--trace=cno-(\d+)-(\d+)""".r

    str match {
      case `fs` ⇒ FSCI()
      case stack(k, h) ⇒ StackCFA(k.toInt, h.toInt)
      case acyclic(h) ⇒ AcyclicCFA(h.toInt)
      case obj(k, h) ⇒ ObjCFA(k.toInt, h.toInt)
      case sig(k, h) ⇒ SigCFA(k.toInt, h.toInt)
      case cxo(k1, h1, k2, h2) ⇒ CFAxObj(k1.toInt,k2.toInt,h1.toInt,h2.toInt)
      case cno(k, h) ⇒ CFAnObj(k.toInt, h.toInt)
      case _ ⇒ sys.error("Invalid trace option: " + str)
    }
  }

  // get the JavaScript program and translate it into a notJS AST,
  // then topologically sort it and return the result
  def readAST( file:String ): Stmt = {
    val prog = RunTranslator.translateFileToProgram(
      new File(file), true ) // we always convert print statements
    Topo.order(prog) // topologically order the Merge nodes
    prog
  }

  // take a Merge state and return the next set of Merge states. a
  // simpler implementation would be the following:
  //
  // var todo = List[State](initς)
  // var done = Set[State]()
  // while ( todo.nonEmpty ) {
  //   todo = todo.flatMap( _.next ).flatMap(
  //     ς ⇒ if ( ς.merge ) { done = done + ς ; None }
  //         else Some(ς)
  //   )
  // }
  // done
  //
  // preliminary experiments suggest the implementation below is
  // marginally faster.
  //
  def process( initς:State ): Set[State] = {
    var todo = List[State](initς)
    var done = Set[State]()
    var ςs = Set[State]()

    while ( todo.nonEmpty ) {
      ςs = todo.head.next
      todo = todo.tail

      // as long as we only get a single next state we can keep
      // transitioning without using the worklist; if we get 0 next
      // states (i.e., we get a Merge state that goes in 'done') or
      // more than one next state then we go back to using the
      // worklist.
        
      while ( ςs.size == 1 ) {
        if ( ςs.head.merge ) {
          done = done + ςs.head
          ςs = Set[State]()
        }
        else ςs = ςs.head.next
      }
      
      ςs foreach (
        (ς) ⇒ if ( ς.merge ) done = done + ς
              else todo = ς :: todo )
    }
    
    done
  }
} // end object notJS

//——————————————————————————————————————————————————————————————————————————————
// Pruning
//
// global data structures for saving unreachable state information at
// a function call and restoring it when the function returns. there's
// one for when we're only pruning Scratchpad (which we have to prune
// for correctness), and one for when we're pruning the store too
// (which is a performance/precision optimization).

object PruneScratch {
  val pruned:HashMap[Trace,Scratchpad] = HashMap()

  def clear = 
    pruned.clear()

  def apply( τ:Trace ): Scratchpad =
    pruned(τ)

  def update( τ:Trace, ß:Scratchpad ): Unit =
    pruned get τ match {
      case Some(ß1) ⇒ pruned(τ) = ß1 ⊔ ß
      case None ⇒ pruned(τ) = ß
    }
}

object PruneStoreToo {
  val pruned:HashMap[Trace,(Store,Scratchpad)] = HashMap()

  def apply( τ:Trace ): (Store, Scratchpad) =
    pruned(τ)

  def update( τ:Trace, σß:(Store, Scratchpad) ): Unit =
    pruned get τ match {
      case Some((σ1, ß1)) ⇒ pruned(τ) = (σ1 ⊔ σß._1, ß1 ⊔ σß._2)
      case None ⇒ pruned(τ) = σß
    }

  def clear = 
    pruned.clear()

  def contains( τ:Trace ): Boolean =
    pruned contains τ
}

//——————————————————————————————————————————————————————————————————————————————
// State: including state transition rules
//
// we optimize the handling of translator-inserted temporary variables
// ("scratch" variables) by using a separate scratchpad memory ß in
// every state that maps the scratch variables directly to BValues
// rather than using the environment and store. this is safe because
// we guarantee that scratch variables cannot cross function
// boundaries (including recursive calls).

case class State( t:Term, ρ:Env, σ:Store, ß:Scratchpad, κs:KStack, τ:Trace ) 
     extends SmartHash {

  // lattice join. we only join at Merge statements with the same
  // trace, so the term and trace should be identical
  def ⊔( ς:State ): State = {
    assert( t == ς.t && τ == ς.τ )
    State( t, ρ ⊔ ς.ρ, σ ⊔ ς.σ, ß ⊔ ς.ß, κs ⊔ ς.κs, τ )
  }

  // predicate: is this a merge point?
  def merge: Boolean =
    t match {
      case StmtT(_:Merge) ⇒ true
      case _ ⇒ false
    }

  // the worklist order, if this is a Merge state
  def order: Int =
    t match {
      case StmtT(s:Merge) ⇒ s.order
      case _ ⇒ sys.error("inconceivable")
    }

  // convenient shorthand for eval. be careful that this is only used
  // when we intend to use ρ and ß; there are some places where we
  // need to eval using a different environment or scratchpad, in
  // which case using this shortcut is a bug.
  def eval( e:Exp ): BValue = 
    Eval.eval(e, ρ, σ, ß)

  // state transition rules
  def next: Set[State] = {
    // !! PDG
    notJS.Mutable.pdg.processing(τ) 
    try { 
      t match {
        case StmtT( s:Stmt ) ⇒ s match {
          // rule 1; we use the trace to make addresses instead of doing
          // it in alloc
          //
          // !! OPT: change Decl to keep xs, es separate so we don't need
          //         to unzip bind
          //
          case d@Decl(bind, s) ⇒ 
            // val (xs, es) = bind.unzip;
            // val as = τ makeAddrs xs
            val declτ = τ update d
            val sτ = declτ update s
            var newσ = σ
            var newρ = ρ
            var curτ = declτ
            bind.foreach {
              case (x, e) ⇒ {
                // !! PDG
                val xτ = τ update (x.id)
                notJS.Mutable.pdg.addCFGEdge(curτ, xτ, DeclVarControl)
                curτ = xτ
                notJS.Mutable.pdg.processing(xτ)    
                val a = τ makeAddr (x)
                newσ = alloc(newσ, a, eval(e))
                newρ = newρ + (x → a)
                
              }
            }
            notJS.Mutable.pdg.addCFGEdge(curτ, sτ, DeclVarControl)
            // val σ1 = alloc(σ, as, es map (eval))
            // val ρ1 = ρ ++ (xs zip as)
            // !! PDG
            // notJS.Mutable.pdg.addCFGEdge(τ update d, τ update s, NormalControl)
            State(s, newρ, newσ, ß, κs, sτ)

          // special case of rule 1
          case sd@SDecl(num, s) ⇒
            // !! PDG
            notJS.Mutable.pdg.addCFGEdge(τ update sd, τ update s, NormalControl)
            State(s, ρ, σ, Scratchpad(num), κs, τ update s)

          // rule 2
          case Seq(s :: ss) ⇒ 
            // !! PDG
            notJS.Mutable.pdg.addCFGEdge(τ, τ update s, NormalControl)
            State(s, ρ, σ, ß, κs push SeqK(ss), τ update s)

          // rules 3,4
          case If(e, s1, s2) ⇒ 
            eval(e).b match {
              case Bool.True  ⇒ 
                // !! PDG
                notJS.Mutable.pdg.addCFGEdge(τ, τ update s1, NormalControl)
                State(s1, ρ, σ, ß, κs, τ update s1)
              
              case Bool.False ⇒ 
                // !! PDG
                notJS.Mutable.pdg.addCFGEdge(τ, τ update s2, NormalControl)
                State(s2, ρ, σ, ß, κs, τ update s2)

              case Bool.⊤     ⇒ 
                // !! PDG
                notJS.Mutable.pdg.addCFGEdge(τ, τ update s1, NormalControl)
                notJS.Mutable.pdg.addCFGEdge(τ, τ update s2, NormalControl)
                Set( State(s1, ρ, σ, ß, κs, τ update s1), 
                     State(s2, ρ, σ, ß, κs, τ update s2))

              case _          ⇒ Set()
            }

          // rules 5, modified to handle scratch variables specially
          case Assign(x, e) ⇒  
            (x, eval(e)) match {
              case (_, BValue.⊥) ⇒ 
                // if the expression evaluates to ⊥ then there shouldn't
                // be a next state
                Set()

              case (x:PVar, bv) ⇒ 
                advanceBV(bv, σ + (ρ(x) → bv), ß, κs, τ)

              case (x:Scratch, bv) ⇒
                advanceBV(bv, σ, ß(x) = bv, κs, τ)
            }

          // rules 6,7
          case w@While(e, s) ⇒ 
            eval(e).b match {
              case Bool.True  ⇒ {
                // !! PDG
                notJS.Mutable.pdg.addCFGEdge(τ, τ update s, NormalControl)
                State(s, ρ, σ, ß, κs push WhileK(e, s, w), τ update s)
              }

              case Bool.False ⇒ {
                // !! PDG TODO: should we add an edge here?
                advanceBV(Undef.BV, σ, ß, κs, τ update w)
              }

              case Bool.⊤     ⇒ {
                // !! PDG 
                notJS.Mutable.pdg.addCFGEdge(τ, τ update s, NormalControl)
                advanceBV(Undef.BV, σ, ß, κs, τ update w) +
                  State(s, ρ, σ, ß, κs push WhileK(e, s, w), τ update s)
              }

              case _          ⇒ Set()
            }

          // rule 8; we use the trace to make an address instead of doing
          // it in allocFun
          //
          // !! OPT: if we've been to this statement before then the store
          //         already has this function in it and all we need to do
          //         is make the address weak
          //
          case Newfun(x, m, n) ⇒ 
            val a1 = τ makeAddr x
            val ρ1 = ρ filter (m.freeVars contains _)
            val σ1 = allocFun( Clo(ρ1, m), eval(n), a1, σ )
            val bv1 = Address.inject(a1)
            x match {
              case pv:PVar ⇒ advanceBV(bv1, σ1 + (ρ(pv) → bv1), ß, κs, τ)
              case sc:Scratch ⇒ advanceBV(bv1, σ1, ß(sc) = bv1, κs, τ)
            }

          // rule 9; we use the trace to make an address instead of doing
          // it in allocObj
          case New(x, e1, e2) ⇒ 
            val a1 = τ makeAddr x
            val (bv1, bv2) = (eval(e1), eval(e2))
            val (σ1, bv) = allocObj( bv1, a1, σ, τ )
            val (σ2, ß1) = x match {
              case pv:PVar ⇒ (σ1 + (ρ(pv) → bv), ß)
              case sc:Scratch ⇒ (σ1, ß(sc) = bv)
            }
            val σ3 = setConstr( σ2, bv2 )
            val exc = if ( !bv1.defAddr ) 
                        Set(State(typeError, ρ, σ, ß, κs, τ))
                      else Set[State]()  
            val (_σ3, _ß1) = refineExc(e1, σ3, ρ, ß1, IsFunc)                      
            exc ++ applyClo( bv1, bv, bv2, x, ρ, _σ3, _ß1, κs, τ )

          // rules 10,11; we use the trace to make an address instead of
          // doing it in allocObj if we need to allocate an object
          case ToObj(x, e) ⇒
            val (noexc, exc) = toObj( eval(e), x, ρ, σ, ß, τ )
            val s1 = noexc match {
              case Some((bv, σ1, ß1)) ⇒ {
                val (_σ1, _ß1) = refineExc(e, σ1, ρ, ß1, IsUndefNull)
                advanceBV(bv, _σ1, _ß1, κs, τ)
              }
              case None ⇒ Set()
            }
            val s2 = exc match {
              case Some(ev) ⇒ advanceEV(ev, ρ, σ, ß, κs, τ)
              case None ⇒ Set()
            }
            s1 ++ s2

          // rule 12,13
          case Update(e1, e2, e3) ⇒ 
            val (noexc, exc) = updateObj( eval(e1), eval(e2), eval(e3), σ )
            val s1 = noexc match {
              case Some((bv, σ1)) ⇒ {
                val (_σ1, _ß) = refineExc(e1, σ1, ρ, ß, IsUndefNull)
                advanceBV(bv, _σ1, _ß, κs, τ)
              }
              case None ⇒ Set()
            }
            val s2 = exc match {
              case Some(ev) ⇒ advanceEV(ev, ρ, σ, ß, κs, τ)
              case None ⇒ Set()
            }
            s1 ++ s2

          // rule 14,15
          case Del(x, e1, e2) ⇒ 
            val (noexc, exc) = delete( eval(e1), eval(e2), x, ρ, σ, ß )
            val s1 = noexc match {
              case Some((σ1, ß1)) ⇒ {
                val (_σ1, _ß1) = refineExc(e1, σ1, ρ, ß1, IsUndefNull)
                advanceBV(Undef.BV, _σ1, _ß1, κs, τ)
              }
              case None ⇒ Set()
            }
            val s2 = exc match {
              case Some(ev) ⇒ advanceEV(ev, ρ, σ, ß, κs, τ)
              case None ⇒ Set()
            }
            s1 ++ s2

          // rule 16
          case Try(s1, x, s2, s3) ⇒ 
            // !! PDG 
            notJS.Mutable.pdg.addCFGEdge(τ, τ update s1, NormalControl)
            State(s1, ρ, σ, ß, κs push TryK(x, s2, s3), τ update s1)

          // rule 17
          case Throw(e) ⇒ 
            advanceEV(EValue(eval(e)), ρ, σ, ß, κs, τ, true)

          // rule 18
          case Jump(lbl, e) ⇒  {
            // !! PDG
            // get next traces, but only local
            κs.getNextTraceSet(τ, σ, true).foreach { nxt ⇒
              notJS.Mutable.pdg.addCFGEdge(τ, nxt, JumpDummyControl)
            }
            advanceJV(JValue(lbl, eval(e)), σ, ß, κs)
          }

          // rule 19
          case Lbl(lbl, s) ⇒
            // !! PDG 
            notJS.Mutable.pdg.addCFGEdge(τ, τ update s, NormalControl)
            State(s, ρ, σ, ß, κs push LblK(lbl), τ update s)

          // rules 20
          case Call(x, e1, e2, e3) ⇒ {
            val bv1 = eval(e1)
            val exc = if (!bv1.defAddr) 
                        Set(State(typeError, ρ, σ, ß, κs, τ))
                      else Set[State]()  
            val (_σ, _ß) = refineExc(e1, σ, ρ, ß, IsFunc)                      
            exc ++ applyClo( bv1, eval(e2), eval(e3), x, ρ, _σ, _ß, κs, τ )
          }

          // rules 21,22
          //
          // !! OPT: we could be more precise by associating keys to the
          //         specific object they came from rather than merging
          //         them all together when there are multiple objects;
          //         then we could refine the address to refer to a
          //         specific object for each iteration of the for loop.
          //
          // !! OPT: investigate adding correlation tracking to for..in
          //         loops a la Sridharan et al
          //
          case f@For(x, e, s) ⇒ 
            val keys = objAllKeys( eval(e), σ )
            if ( keys.nonEmpty ) {
              // since we're not doing anything special precision-wise to
              // handle for..in all of the keys will end up being joined
              // together as the value of x; we may as well go ahead and
              // join them now so we can produce a single State instead of
              // a set of States, thus at least giving us better performance.
              val uber = Str.inject( keys.reduceLeft( (acc, key) ⇒ acc ⊔ key ) )
              // !! PDG 
              notJS.Mutable.pdg.addCFGEdge(τ, τ update s, NormalControl)
              x match {
                case pv:PVar ⇒
                  State(s, ρ, σ + (ρ(pv) → uber), ß, κs push ForK(uber, x, s, f), τ update s)
                
                case sc:Scratch ⇒
                  State(s, ρ, σ, ß(sc) = uber, κs push ForK(uber, x, s, f), τ update s)
              }
            }
            else
              advanceBV(Undef.BV, σ, ß, κs, τ update f)
          
          // this is semantically a noop; we use it as a marker for where
          // to merge states together for control-flow sensitivity
          case Merge() ⇒
            advanceBV(Undef.BV, σ, ß, κs, τ)

          // add to output map in testing mode; otherwise a noop
          case p @ Print(e) ⇒ {
            if ( notJS.Mutable.inPostFixpoint ) 
              notJS.Mutable.outputMap(p.id) = 
                notJS.Mutable.outputMap(p.id) + eval(e)
            advanceBV(Undef.BV, σ, ß, κs, τ)
          } 

          // we can only reach here if we get an empty Seq statement,
          // which shouldn't be possible
          case _ ⇒ 
            sys.error("malformed program")
        }

        case ValueT( bv:BValue ) ⇒
          advanceBV(bv, σ, ß, κs, τ)

        case ValueT( ev:EValue ) ⇒
          advanceEV(ev, ρ, σ, ß, κs, τ)

        case ValueT( jv:JValue ) ⇒
          advanceJV(jv, σ, ß, κs)
      }      
    } catch {
      case AddrNotFound ⇒ Set[State]()
      case exc: Throwable ⇒ throw exc
    }
  }


  def advanceBV( bv:BValue, σ1:Store, ß1:Scratchpad, κs1:KStack, τ1: Trace ): Set[State] = {
    κs1.top match {
      // rule 23
      case SeqK(s :: ss) ⇒
        // !! PDG 
        notJS.Mutable.pdg.addCFGEdge(τ1, τ1 update s, NormalControl)
        State(s, ρ, σ1, ß1, κs1 repl SeqK(ss), τ1 update s)

      // rule 24
      case SeqK(_) ⇒
        advanceBV( Undef.BV, σ1, ß1, κs1.pop, τ1 )

      // rules 25,26
      case WhileK(e, s, w) ⇒
        // !! PDG 
        notJS.Mutable.pdg.addCFGEdge(τ1, τ1 update w, NormalControl)
        Eval.eval(e, ρ, σ1, ß1).b match {
          case Bool.True  ⇒ {
            // !! PDG 
            notJS.Mutable.pdg.addCFGEdge(τ1 update w, τ1 update s, NormalControl)
            State(s, ρ, σ1, ß1, κs1, τ1 update s)
          }
          case Bool.False ⇒ advanceBV(Undef.BV, σ1, ß1, κs1.pop, τ1 update w)
          case Bool.⊤     ⇒ {
            // !! PDG 
            notJS.Mutable.pdg.addCFGEdge(τ1 update w, τ1 update s, NormalControl)
            advanceBV(Undef.BV, σ1, ß1, κs1.pop, τ1 update w) + 
              State(s, ρ, σ1, ß1, κs1, τ1 update s)
          }
          case _          ⇒ Set()
        }

      // rules 27,28
      case ForK(bv1, x, s, f) ⇒
        // !! PDG 
        notJS.Mutable.pdg.addCFGEdge(τ1, τ1 update f, NormalControl)
        notJS.Mutable.pdg.addCFGEdge(τ1 update f, τ1 update s, NormalControl)
        advanceBV(Undef.BV, σ1, ß1, κs1.pop, τ1 update f) + (x match {
          case pv:PVar ⇒ State(s, ρ, σ1 + (ρ(pv) → bv1), ß1, κs1, τ1 update s)
          case sc:Scratch ⇒ State(s, ρ, σ1, ß1(sc) = bv1, κs1, τ1 update s)
        })

      // rule 29 (also look in EValue cases)
      case AddrK(a, m) ⇒ 
        // do lightweight GC if it is enabled
        val σ2 = 
          if ( notJS.Mutable.lightGC ) σ1.lightgc( m.cannotEscape )
          else σ1

        // conservatively make all potentially escaping addresses weak
        val σ3 = σ2.weaken( m.canEscapeVar, m.canEscapeObj )

        (σ3 getKont a) flatMap (advanceBV(bv, σ3, ß1, _, τ1))

      // rules 30,31,32
      case RetK(x, ρc, isctor, τc) ⇒ { 
        // restore pruned information; this needs to happen after
        // lightgc but before fullgc
        val (σ2, ß2) =
          if ( notJS.Mutable.pruneStore ) {
            val (σpruned, ßpruned) = PruneStoreToo(τc)
            (σ1 ⊔ σpruned, ßpruned)
          }
          else {
            val ßpruned = PruneScratch(τc)
            (σ1, ßpruned)
          }

        // do full GC if it is enabled
        val σ3 =
          if ( notJS.Mutable.fullGC ) {
            val vroots = ρc.addrs
            val oroots = bv.as ++ ß2.addrs ++ Init.keepInStore
            val kroots = κs1.last match {
              case AddrK(a, _) ⇒ Addresses(a)
              case _ ⇒ Addresses()
            }
            σ2.fullgc( vroots, oroots, kroots )
          }
          else σ2
        
        // !! PDG         
        // updated trace reflects after returning from the call 
        val updatedτ = τ1 update τc 
        // ret trace reflects the artificially inserted ret node 
        // corresponding to the call node
        val retτ = PDGInterface.getRetNode(updatedτ)
        notJS.Mutable.pdg.addCFGEdge(τ1, retτ, ReturnControl)
        notJS.Mutable.pdg.addDDGEdge(τ1, retτ, WeakData)
        // we update the current trace to the one after return
        // as we are doing assignments/evaluations after the return
        // in the following code
        notJS.Mutable.pdg.processing(retτ) 

        // handle return from regular calls or constructor calls that
        // return an address
        val call =
          if ( !isctor || (bv.as.size > 0) ) {
            val bv1 = if ( !isctor ) bv else Addresses.inject(bv.as)
            x match {
              case pv:PVar ⇒ 
                Set(State(bv1, ρc, σ3+(ρc(pv)→bv1), ß2, κs1.pop, updatedτ))

              case sc:Scratch ⇒
                Set(State(bv1, ρc, σ3, ß2(sc) = bv1, κs1.pop, updatedτ))
            }
          }
          else Set()

        // handle return from constructor calls that don't return an address
        val ctor = 
          if ( isctor && !bv.defAddr ) {
            x match {
              case pv:PVar ⇒ 
                val t1 = Eval.eval(x, ρc, σ3, ß2)
                Set(State(t1, ρc, σ3, ß2, κs1.pop, updatedτ))

              case sc:Scratch ⇒ 
                Set(State(ß2(sc), ρc, σ3, ß2, κs1.pop, updatedτ))
            }
          } 
          else Set()

        call ++ ctor
      }

      // rule 34
      case TryK(_, _, s3) ⇒
        // !! PDG 
        notJS.Mutable.pdg.addCFGEdge(τ1, τ1 update s3, NormalControl)
        State(s3, ρ, σ1, ß1, κs1 repl FinK(Set(Undef.BV)), τ1 update s3)

      // rule 37
      case CatchK(s3) ⇒
        // !! PDG 
        notJS.Mutable.pdg.addCFGEdge(τ1, τ1 update s3, NormalControl)
        State(s3, ρ, σ1, ß1, κs1 repl FinK(Set(Undef.BV)), τ1 update s3)

      // rule 39
      case FinK(vs) ⇒
        vs flatMap {
          case _:BValue  ⇒ advanceBV(bv, σ1, ß1, κs1.pop, τ1)
          case ev:EValue ⇒ advanceEV(ev, ρ, σ1, ß1, κs1.pop, τ1)
          case jv:JValue ⇒ advanceJV(jv, σ1, ß1, κs1.pop)
        }

      // rule 40 (also look in JValue cases)
      case LblK(_) ⇒
        advanceBV(bv, σ1, ß1, κs1.pop, τ1)

      case HaltK ⇒
        // !! PDG
        notJS.Mutable.pdg.addCFGEdge(τ1, Haltingτ, HaltControl)
        Set()
    }
  }

  // some ideas for optimizing exception handling:
  //
  // + phasing: run the worklist in two phases, normal and exc. in the
  //   normal phase store exception states without handling them; in the
  //   exc state process all the exceptions (merging them as desired),
  //   then go back to the normal phase.
  //
  def advanceEV( ev:EValue, ρ1:Env, σ1:Store, ß1:Scratchpad, κs1:KStack, passedτ:Trace, explicit: Boolean = false ) : Set[State] = {
    val addrsSeen = HashSet[Address]()

    // !! PDG
    def CFGEdge(from: Trace, to: Trace) {
      if (explicit) 
        notJS.Mutable.pdg.addCFGEdge(from, to, ExpExcControl)
      else 
        notJS.Mutable.pdg.addCFGEdge(from, to, ImpExcControl)

      // add dummy edges to each of the next trace sets  
      // get even non local next traces
      κs1.getNextTraceSet(from, σ1, false) foreach { nxt ⇒ 
        notJS.Mutable.pdg.addCFGEdge(from, nxt, DummyControl)
      }
    }

    def DDGEdge(from: Trace, to: Trace) {
      if (explicit) 
        notJS.Mutable.pdg.addDDGEdge(from, to, WeakData)
    }

    def innerAdvance( ev:EValue, ρ1:Env, σ1:Store, ß1:Scratchpad, κs1:KStack, τ1:Trace ) : Set[State] = {
      κs1.exc.head match {
        // no exception handler
        case 0 ⇒
          // !! PDG
          // we do not add this edge since we do not care about termination leaks
          // CFGEdge(passedτ, Haltingτ)
          Set[State]()

        // exception handler in caller
        case 1 ⇒
          val AddrK(a, m) = κs1.last
          if ( !addrsSeen(a) ) {
            addrsSeen += a

            // do lightweight GC if it is enabled
            val σ2 =
              if ( notJS.Mutable.lightGC ) σ1.lightgc( m.cannotEscape )
              else σ1
        
            // conservatively make all potentially escaping addresses weak
            val σ3 = σ2.weaken( m.canEscapeVar, m.canEscapeObj )
            
            (σ3 getKont a) flatMap ( κs2 ⇒ {
              val RetK(_, ρc, _, τc) = κs2.top

              // restore pruned information; this needs to happen after
              // lightgc but before fullgc
              val (σ4, ß2) =
                if ( notJS.Mutable.pruneStore ) {
                  val (σpruned, ßpruned) = PruneStoreToo(τc)
                  (σ3 ⊔ σpruned, ßpruned)
                }
                else {
                  val ßpruned = PruneScratch(τc)
                  (σ3, ßpruned)
                }

              // do full GC if it is enabled
              val σ5 =
                if ( notJS.Mutable.fullGC ) {
                  val vroots = ρc.addrs
                  val oroots = ev.bv.as ++ ß2.addrs ++ Init.keepInStore
                  val kroots = κs2.last match {
                    case AddrK(a, _) ⇒ Addresses(a)
                    case _ ⇒ Addresses()
                  }
                  σ4.fullgc( vroots, oroots, kroots )
                }
                else σ4

              innerAdvance(ev, ρc, σ5, ß2, κs2.pop, τ1 update τc)
            } )
          }
          else Set[State]()

        // exception handler in local function
        case 2 ⇒
          val κs2 = κs1.toHandler
          κs2.top match {
            case TryK(x, s2, s3) ⇒
              val updatedτ = τ1 update s2
              // !! PDG
              CFGEdge(passedτ, updatedτ)
              // we also add an extra data dependency edge
              DDGEdge(passedτ, updatedτ)
              // the copying of exeption value to catch variable
              // is under the updated trace
              notJS.Mutable.pdg.processing(updatedτ) 
              State(s2, ρ1, σ1 + (ρ1(x) → ev.bv), ß1, κs2 repl CatchK(s3), updatedτ)

            case CatchK(s3) ⇒
              // !! PDG
              CFGEdge(passedτ, τ1 update s3)
              State(s3, ρ1, σ1, ß1, κs2 repl FinK(Set(ev)), τ1 update s3)

            case _ ⇒
              sys.error("inconceivable")
          }
      }
    }

    if ( κs1.exc.head != 0 ) innerAdvance(ev, ρ1, σ1, ß1, κs1, passedτ)
    else {
      // !! PDG
      // we do not add this edge because we do not care about termination leaks
      // CFGEdge(passedτ, Haltingτ)
      Set[State]()
    }
  }

  def advanceJV( jv:JValue, σ1:Store, ß1:Scratchpad, κs1:KStack ): Set[State] =
    κs1.top match {
      // rule 35
      case TryK(_, _, s3) ⇒
        State(s3, ρ, σ1, ß1, κs1 repl FinK(Set(jv)), τ update s3)

      // rule 38 (also look in EValue cases)
      case CatchK(s3) ⇒
        State(s3, ρ, σ1, ß1, κs1 repl FinK(Set(jv)), τ update s3)

      // rule 40 (also look in BValue cases)
      case LblK(lbl) if lbl == jv.lbl ⇒
        advanceBV(jv.bv, σ1, ß1, κs1.pop, τ)

      // we hit the end of the program with no matching labeled statement
      case HaltK ⇒
        Set()

      // rules 41,42 (also look in EValue cases)
      case _ ⇒
        val κs2 = κs1.toSpecial(jv.lbl)
        advanceJV(jv, σ1, ß1, κs2)
    }

    implicit def s2ss( ς:State ): Set[State] = Set(ς)
}

// for ordering states in the priority queue; the entry with a lower
// order has a higher priority
//
// this implementation allows different states with the same order but
// different traces to be intermingled. to fix that we would need to
// define compare for Traces; preliminary experiments suggest this
// isn't worth the effort.
//
object OrderWorklist extends Ordering[(Int, Trace)] {
  def compare( p1:(Int, Trace), p2:(Int, Trace) ): Int =
    p2._1 compare p1._1
}
