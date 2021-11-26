package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.stats.*;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;

import java.util.*;

public final class SwitchExpressionHelper {
  public static boolean processSwitchExpressions(Statement root) {
    boolean ret = processSwitchExpressionsRec(root);

    if (ret) {
      SequenceHelper.condenseSequences(root);
    }

    return ret;
  }

  private static boolean processSwitchExpressionsRec(Statement stat) {
    boolean ret = false;
    for (Statement st : new ArrayList<>(stat.getStats())) {
      ret |= processSwitchExpressionsRec(st);
    }

    if (stat.type == Statement.TYPE_SWITCH) {
      ret |= processStatement((SwitchStatement) stat);
    }

    return ret;
  }

  private static boolean processStatement(SwitchStatement stat) {
    if (stat.isPhantom()) {
      return false;
    }

    // At this stage, there are no variable assignments
    // So we need to figure out which variable, if any, this switch statement is an expression of and make it generate.
    VarVersionPair foundVar = null;
    VarExprent found = null;
    for (Statement caseStat : stat.getCaseStatements()) {
      Set<StatEdge> edges = new HashSet<>();
      TryWithResourcesProcessor.findEdgesLeaving(caseStat, caseStat, edges);
      for (StatEdge edge : edges) {
        // There's a continue- can't be a switch expression
        if (edge.getType() == StatEdge.TYPE_CONTINUE) {
          return false;
        }
      }

      List<Exprent> exprents = caseStat.getExprents();

      // TODO: improve checking, possibly use SSA
      if (exprents != null && !exprents.isEmpty()) {
        Exprent exprent = exprents.get(exprents.size() - 1);

        // We need all break edges to be enclosed in the current switch statement, as otherwise they could be breaking to statements beyond our scope, which messes up control flow
        List<StatEdge> breaks = caseStat.getSuccessorEdges(StatEdge.TYPE_BREAK);
        if (breaks.isEmpty()) {
          return false; // TODO: handle switch expression with fallthrough!
        }

        if (exprent.type == Exprent.EXPRENT_ASSIGNMENT && ((AssignmentExprent)exprent).getLeft().type == Exprent.EXPRENT_VAR) {
          VarVersionPair var = (((VarExprent) ((AssignmentExprent) exprent).getLeft())).getVarVersionPair();

          if (breaks.get(0).closure != stat) {
            return false;
          }

          if (foundVar == null) {
            foundVar = var;
            found = (((VarExprent) ((AssignmentExprent) exprent).getLeft()));
          } else {
            if (!foundVar.equals(var)) {
              return false;
            }
          }
        } else if (exprent.type == Exprent.EXPRENT_EXIT && ((ExitExprent)exprent).getExitType() == ExitExprent.EXIT_RETURN) {
          // TODO: check for successors to dummy exit!
          return false; // Has a return, cannot be a switch statement
        }
      }
    }

    if (foundVar == null || found == null) {
      return false;
    }

    List<StatEdge> sucs = stat.getSuccessorEdges(StatEdge.TYPE_REGULAR);

    // TODO: should we be using getbasichead?
    if (!sucs.isEmpty()) {

      Statement suc = sucs.get(0).getDestination();
      if (suc.type != Statement.TYPE_BASICBLOCK) { // make basic block if it isn't found
        Statement oldSuc = suc;

        suc = BasicBlockStatement.create();
        SequenceStatement seq = new SequenceStatement(stat, suc);

        seq.setParent(stat.getParent());

        stat.replaceWith(seq);

        seq.setAllParent();

        // Replace successors with the new basic block
        for (Statement st : stat.getCaseStatements()) {
          for (StatEdge edge : st.getAllSuccessorEdges()) {
            if (edge.getDestination() == oldSuc) {
              st.removeSuccessor(edge);

              st.addSuccessor(new StatEdge(edge.getType(), st, suc, seq));
            }
          }
        }

        // Control flow from new basic block to the next one
        suc.addSuccessor(new StatEdge(StatEdge.TYPE_REGULAR, suc, oldSuc, seq));
      }

      stat.setPhantom(true);

      for (Statement st : stat.getCaseStatements()) {
        Map<Exprent, YieldExprent> replacements = new HashMap<>();

        findReplacements(st, foundVar, replacements);

        // Replace exprents that we found
        if (!replacements.isEmpty()) {
          // Replace the assignments with yields, this allows 2 things:
          // 1) Not having to replace the assignments later on
          // 2) Preventing the variable assignment tracker from putting variable definitions too early because the assignments are no longer in the phantom statement
          replace(st, replacements);
        }
      }

      List<Exprent> exprents = suc.getExprents();

      VarExprent vExpr = new VarExprent(found.getIndex(), found.getVarType(), found.getProcessor());
      vExpr.setStack(true); // We want to inline
      AssignmentExprent toAdd = new AssignmentExprent(vExpr, new SwitchExprent(stat, found.getExprType(), false), null);

      exprents.add(0, toAdd);

      // move exprents from switch head to successor
      List<Exprent> firstExprents = stat.getFirst().getExprents();
      if (firstExprents != null && !firstExprents.isEmpty()) {
        int i = 0;
        for (Iterator<Exprent> iterator = firstExprents.iterator(); iterator.hasNext(); ) {
          Exprent ex = iterator.next();
          if (ex.type == Exprent.EXPRENT_ASSIGNMENT && ((AssignmentExprent) ex).getLeft().type == Exprent.EXPRENT_VAR) {
            if (((VarExprent) ((AssignmentExprent) ex).getLeft()).isStack()) {
              exprents.add(i, ex);
              i++;
              iterator.remove();
            }
          }
        }
      }

      return true;
  }

    return false;
  }

  private static void findReplacements(Statement stat, VarVersionPair var, Map<Exprent, YieldExprent> replacements) {
    if (stat.getExprents() != null) {
      for (Exprent e : stat.getExprents()) {
        // Check for "var10000 = <value>" within the exprents
        if (e.type == Exprent.EXPRENT_ASSIGNMENT) {
          AssignmentExprent assign = ((AssignmentExprent) e);

          if (assign.getLeft().type == Exprent.EXPRENT_VAR) {
            if (((VarExprent) assign.getLeft()).getIndex() == var.var) {
              // Make yield with the right side of the assignment
              replacements.put(assign, new YieldExprent(assign.getRight(), assign.getExprType()));
            }
          }
        }
      }
    }

    for (Statement st : stat.getStats()) {
      findReplacements(st, var, replacements);
    }
  }

  private static void replace(Statement stat, Map<Exprent, YieldExprent> replacements) {
    for (Map.Entry<Exprent, YieldExprent> entry : replacements.entrySet()) {
      stat.replaceExprent(entry.getKey(), entry.getValue());
    }

    for (Statement st : stat.getStats()) {
      replace(st, replacements);
    }
  }

  public static boolean hasSwitchExpressions(RootStatement statement) {
    return statement.mt.getBytecodeVersion().hasSwitchExpressions() && DecompilerContext.getOption(IFernflowerPreferences.SWITCH_EXPRESSIONS);
  }
}