package com.apimarketplace.orchestrator.archunit;

import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A split context that is not stamped with its epoch silently disables the staleness check.
 *
 * <p>{@link SplitContextManager} caches a split's scope in the pod's heap under a key that
 * carries no epoch. With several orchestrator replicas, an async agent delivery (or a signal
 * resume) is handled by whichever pod consumed the message, which may be a pod that ran an
 * EARLIER epoch of the same run and still holds its scope.
 * {@code SplitContextManager.restoreContext(runId, nodeId, splitItemData, epoch)} rebuilds such
 * a scope instead of reusing it - but only if both sides carry a real epoch. A creator that
 * calls an epoch-less overload stamps {@link com.apimarketplace.orchestrator.execution.v2.split.SplitContext#UNKNOWN_EPOCH},
 * which is never stale, so that whole workflow shape keeps the pre-2026-08-14 bug (prod run
 * {@code run_<id>}: the fan-out replayed the previous epoch's items).
 *
 * <p>The overloads without an epoch are kept for tests and for callers that genuinely cannot
 * know one; production code must not use them. This is exactly how the fix was first shipped
 * incomplete: {@code SplitAwareNodeExecutor.executeNestedSplitFlat} had the
 * {@code ExecutionContext} in hand and still called the 4-arg overload, so nested flat splits
 * were left unprotected while every other path was fixed.
 *
 * <p><b>What a green run does NOT prove</b>, so nobody reads more into it:
 * <ul>
 *   <li>It checks the OVERLOAD, not the VALUE. A call site passing the wrong int (the workflow
 *       item index instead of the epoch - they sit two arguments apart in
 *       {@code executeNestedSplitFlat}) satisfies this rule. Each of the 8 production call
 *       sites is therefore ALSO value-pinned by a unit test using deliberately different
 *       numbers: {@code AgentAsyncCompletionSplitEpochRestoreTest} (epoch 97 vs item index 3),
 *       {@code SignalResumeServiceTest#shouldRestoreSplitContext} (7),
 *       {@code SplitNodeExecutorTest} (42, both the populated and the empty branch), and
 *       {@code SplitAwareNodeExecutorTest#nestedSplitFlatStampsTheEpoch(WhenNoInnerItems)}
 *       (77 vs item index 2, both branches).</li>
 *   <li>It cannot see through {@code SplitNodeExecutor.epochOf}, which returns
 *       {@code UNKNOWN_EPOCH} for a null context. Both engines always pass a real context, so
 *       that is a defensive branch rather than a live hole.</li>
 * </ul>
 */
@DisplayName("SplitContextManager callsite invariant: production must call an epoch-carrying overload")
class SplitContextEpochStampInvariantTest {

    private static final String MANAGER = SplitContextManager.class.getName();

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.apimarketplace.orchestrator");

    @Test
    @DisplayName("every production createContext call passes an int epoch (last, or the epoch-first bucket overload)")
    void createContextCallsCarryEpoch() {
        // Both shapes are legitimate: createContext(runId, splitNodeId, itemIndex, scope, items,
        // EPOCH) and the epoch-bucket overload createContext(runId, EPOCH, splitNodeId, ...).
        // Requiring "last" specifically would flag a future, correct use of the second one.
        List<String> offenders = callsTo("createContext").stream()
            .filter(call -> !carriesAnIntEpoch(call))
            .map(SplitContextEpochStampInvariantTest::describe)
            .toList();

        assertThat(offenders)
            .as("these call sites build a split scope stamped UNKNOWN_EPOCH, so a delivery on a "
                + "pod holding an earlier epoch's scope will reuse it instead of rebuilding it - "
                + "pass the ExecutionContext's epoch (see SplitNodeExecutor.epochOf)")
            .isEmpty();
    }

    @Test
    @DisplayName("every production restoreContext call carries the delivery's epoch")
    void restoreContextCallsCarryEpoch() {
        List<String> offenders = callsTo("restoreContext").stream()
            .filter(call -> !carriesAnIntEpoch(call))
            .map(SplitContextEpochStampInvariantTest::describe)
            .toList();

        assertThat(offenders)
            .as("without the epoch the restore falls back to 'a context already exists -> skip', "
                + "which is the reuse this rule exists to prevent - pass PendingAgent.epoch() / "
                + "SignalWaitEntity.getEpoch()")
            .isEmpty();
    }

    @Test
    @DisplayName("the async delivery restores the split scope, and does it BEFORE it uses that scope")
    void deliveryPathRestoresBeforeUsingTheScope() {
        // AgentAsyncCompletionSplitEpochRestoreTest invokes restoreSplitContextIfAny by
        // reflection, so it proves "if called, the epoch is forwarded" - not "it is still called,
        // first". Both losses are silent: delete the call, or move it after the scope is read,
        // and every successor fans out over whatever scope the pod happens to hold.
        //
        // Structural proxy, with two limits worth knowing: it compares SOURCE LINES, not
        // execution order (a call relocated into an earlier branch would still pass), and it
        // goes red on a legitimate refactor that moves the restore into a sub-helper. That is
        // the intended trade - re-point it at the new call site rather than deleting it.
        // persistStepResult is the anchor; the scope's first real consumer lives further down
        // the delivery in storeSplitBatchInContext, which is a different method and so cannot
        // be compared by line here.
        var deliverCalls = PRODUCTION_CLASSES
            .get("com.apimarketplace.orchestrator.execution.v2.async.AgentAsyncCompletionService")
            .getMethodCallsFromSelf().stream()
            .filter(call -> call.getOrigin().getName().equals("deliverUnderLock"))
            .toList();

        int restoreLine = deliverCalls.stream()
            .filter(call -> call.getName().equals("restoreSplitContextIfAny"))
            .mapToInt(JavaMethodCall::getLineNumber)
            .min()
            .orElse(Integer.MAX_VALUE);
        int firstScopeUseLine = deliverCalls.stream()
            .filter(call -> call.getName().equals("persistStepResult"))
            .mapToInt(JavaMethodCall::getLineNumber)
            .min()
            .orElse(Integer.MAX_VALUE);

        assertThat(restoreLine)
            .as("deliverUnderLock must call restoreSplitContextIfAny (found none)")
            .isNotEqualTo(Integer.MAX_VALUE);
        assertThat(firstScopeUseLine)
            .as("expected deliverUnderLock to still persist/rebuild after the restore; if that "
                + "changed, re-point this guard at whatever now consumes the split scope")
            .isNotEqualTo(Integer.MAX_VALUE);
        assertThat(restoreLine)
            .as("the split scope must be rebuilt BEFORE the delivery pipeline reads it")
            .isLessThan(firstScopeUseLine);
    }

    @Test
    @DisplayName("the rule can actually fail: an epoch-less overload is still on the API")
    void ruleIsNotVacuous() {
        // A guard that no longer matches anything passes silently forever. A violation must stay
        // EXPRESSIBLE: an overload with no int at all in an epoch position (so neither the last
        // nor the second parameter), which is what the epoch-less createContext overloads are.
        assertThat(SplitContextManager.class.getDeclaredMethods())
            .as("an epoch-less createContext overload must remain on the API, or this rule "
                + "cannot be violated and passes vacuously")
            .anyMatch(m -> m.getName().equals("createContext")
                && m.getParameterCount() >= 2
                && !m.getParameterTypes()[m.getParameterCount() - 1].equals(int.class)
                && !m.getParameterTypes()[1].equals(int.class));
        assertThat(SplitContextManager.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("restoreContext") && m.getParameterCount() == 3);
        // And the scan must actually see production call sites (a broken importer or an
        // over-eager ImportOption would otherwise make both rules trivially true).
        assertThat(callsTo("createContext")).isNotEmpty();
        assertThat(callsTo("restoreContext")).isNotEmpty();
    }

    private static List<JavaMethodCall> callsTo(String methodName) {
        List<JavaMethodCall> calls = new ArrayList<>();
        PRODUCTION_CLASSES.forEach(javaClass -> {
            if (javaClass.getName().equals(MANAGER)) {
                return; // the manager's own delegating overloads are the point of the overloads
            }
            javaClass.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().getName().equals(MANAGER))
                .filter(call -> call.getName().equals(methodName))
                .forEach(calls::add);
        });
        return calls;
    }

    /**
     * True when the resolved overload takes an {@code int} that can only be the epoch: the last
     * parameter (every epoch-carrying signature) or the second one (the epoch-bucket overload
     * {@code createContext(runId, epoch, splitNodeId, workflowItemIndex, parentScopeKey, items)}).
     * True of TODAY's signatures: {@code workflowItemIndex} sits at position 3+ in every one of
     * them, so it cannot satisfy this on its own. This is a shape check, not a semantic one - a
     * future overload ending in another {@code int} (say {@code maxItems}), or starting with one,
     * would pass. Change a signature, revisit this predicate.
     */
    private static boolean carriesAnIntEpoch(JavaMethodCall call) {
        List<JavaClass> parameters = call.getTarget().getRawParameterTypes();
        if (parameters.isEmpty()) {
            return false;
        }
        boolean epochLast = "int".equals(parameters.get(parameters.size() - 1).getName());
        boolean epochFirst = parameters.size() >= 2 && "int".equals(parameters.get(1).getName());
        return epochLast || epochFirst;
    }

    private static String describe(JavaMethodCall call) {
        return call.getOriginOwner().getSimpleName() + "." + call.getOrigin().getName()
            + " -> " + call.getTarget().getFullName();
    }
}
