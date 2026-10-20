package io.floci.gcp.services.iam;

import com.google.api.expr.v1alpha1.Decl;
import com.google.api.expr.v1alpha1.Type;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.projectnessie.cel.Ast;
import org.projectnessie.cel.Env;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.Program;
import org.projectnessie.cel.ProgramOption;
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.common.operators.Operator;
import org.projectnessie.cel.common.types.BoolT;
import org.projectnessie.cel.common.types.Overloads;
import org.projectnessie.cel.common.types.ref.Val;
import org.projectnessie.cel.interpreter.functions.Overload;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Nessie CEL-Java implementation of Floci's Version 1 IAM Conditions profile.
 *
 * <p>The custom environment intentionally has no standard library. Only declarations and runtime
 * overloads listed here are available, so unsupported functions fail type checking without
 * Floci-side source or AST filtering.</p>
 *
 * <p>{@code resource.matchTag} and {@code resource.matchTagId} read the effective tags of the resource
 * under evaluation through {@link IamTagResolver}. The resource is held in a thread-local for the length
 * of one evaluation, because compiled programs are cached and shared across resources.</p>
 */
@ApplicationScoped
public class NessieIamConditionEvaluator implements IamConditionEvaluator {

    private static final int DEFAULT_CACHE_CAPACITY = 256;
    private static final Set<String> RUNTIME_OPERATORS = Set.of(
            Operator.LogicalNot.id,
            Operator.Less.id,
            Overloads.TypeConvertTimestamp);
    static final String MATCH_TAG = "resource.matchTag";
    static final String MATCH_TAG_ID = "resource.matchTagId";
    private static final ThreadLocal<Evaluation> CURRENT = new ThreadLocal<>();

    private final Env environment;
    private final Clock clock;
    private final int cacheCapacity;
    private final Map<String, Program> programs;
    private final Supplier<IamTagResolver> tagResolver;

    public NessieIamConditionEvaluator() {
        this(Clock.systemUTC(), DEFAULT_CACHE_CAPACITY, () -> null);
    }

    @Inject
    public NessieIamConditionEvaluator(Instance<IamTagResolver> tagResolvers) {
        this(Clock.systemUTC(), DEFAULT_CACHE_CAPACITY, () -> tagResolvers.isResolvable() ? tagResolvers.get() : null);
    }

    NessieIamConditionEvaluator(Clock clock, int cacheCapacity) {
        this(clock, cacheCapacity, () -> null);
    }

    NessieIamConditionEvaluator(Clock clock, int cacheCapacity, Supplier<IamTagResolver> tagResolver) {
        this.tagResolver = Objects.requireNonNull(tagResolver, "tagResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (cacheCapacity <= 0) {
            throw new IllegalArgumentException("cacheCapacity must be positive");
        }
        this.cacheCapacity = cacheCapacity;
        this.environment = Env.newCustomEnv(EnvOption.declarations(profileDeclarations()));
        this.programs = new LinkedHashMap<>(cacheCapacity, 0.75f, true);
    }

    @Override
    public void validate(IamCondition condition) {
        programFor(condition);
    }

    @Override
    public boolean matches(IamCondition condition, IamResource resource) {
        Objects.requireNonNull(resource, "resource");
        CURRENT.set(new Evaluation(resource, tagResolver.get()));
        try {
            Program.EvalResult result = programFor(condition).eval(Map.of(
                    "resource.service", resource.service(),
                    "resource.type", resource.type(),
                    "resource.name", resource.name(),
                    "request.time", clock.instant()));
            return result.getVal().value() instanceof Boolean value && value;
        } catch (RuntimeException e) {
            return false;
        } finally {
            CURRENT.remove();
        }
    }


    int cachedProgramCount() {
        synchronized (programs) {
            return programs.size();
        }
    }

    private Program programFor(IamCondition condition) {
        Objects.requireNonNull(condition, "condition");
        String expression = condition.expression();
        synchronized (programs) {
            Program cached = programs.get(expression);
            if (cached != null) {
                return cached;
            }
            Program compiled = compile(expression);
            if (programs.size() == cacheCapacity) {
                programs.remove(programs.keySet().iterator().next());
            }
            programs.put(expression, compiled);
            return compiled;
        }
    }

    private Program compile(String expression) {
        try {
            Env.AstIssuesTuple result = environment.compile(expression);
            if (result.hasIssues() || result.getAst() == null) {
                throw GcpException.invalidArgument("Unsupported IAM condition expression");
            }
			Ast ast = result.getAst();
			if (ast.getResultType().getPrimitive() != Type.PrimitiveType.BOOL) {
				throw GcpException.invalidArgument("IAM condition expression must evaluate to a boolean");
			}
			return environment.program(ast, ProgramOption.functions(profileOverloads()));
        } catch (GcpException e) {
            throw e;
        } catch (RuntimeException e) {
            throw GcpException.invalidArgument("Unsupported IAM condition expression");
        }
    }

    /**
     * Declares the complete compile-time surface of the Version 1 profile.
     *
     * <p>{@link Env#newCustomEnv(EnvOption...)} intentionally omits CEL's standard library, so
     * declarations are required even for familiar operators. They let CEL type-check only the
     * signatures Floci supports; {@link #profileOverloads()} separately supplies the runtime
     * implementations that this custom environment needs.</p>
     */
    private static List<Decl> profileDeclarations() {
        return List.of(
                Decls.newFunction(Operator.LogicalAnd.id,
                        Decls.newOverload(Overloads.LogicalAnd, List.of(Decls.Bool, Decls.Bool), Decls.Bool)),
                Decls.newFunction(Operator.LogicalOr.id,
                        Decls.newOverload(Overloads.LogicalOr, List.of(Decls.Bool, Decls.Bool), Decls.Bool)),
                Decls.newFunction(Operator.LogicalNot.id,
                        Decls.newOverload(Overloads.LogicalNot, List.of(Decls.Bool), Decls.Bool)),
                Decls.newFunction(Operator.Equals.id,
                        Decls.newOverload(Overloads.Equals, List.of(Decls.String, Decls.String), Decls.Bool)),
                Decls.newFunction(Operator.NotEquals.id,
                        Decls.newOverload(Overloads.NotEquals, List.of(Decls.String, Decls.String), Decls.Bool)),
                Decls.newFunction(Operator.Less.id,
                        Decls.newOverload(Overloads.LessTimestamp,
                                List.of(Decls.Timestamp, Decls.Timestamp), Decls.Bool)),
                Decls.newFunction(Overloads.StartsWith,
                        Decls.newInstanceOverload(Overloads.StartsWithString,
                                List.of(Decls.String, Decls.String), Decls.Bool)),
                Decls.newFunction(Overloads.EndsWith,
                        Decls.newInstanceOverload(Overloads.EndsWithString,
                                List.of(Decls.String, Decls.String), Decls.Bool)),
                Decls.newFunction(Overloads.TypeConvertTimestamp,
                        Decls.newOverload(Overloads.StringToTimestamp, List.of(Decls.String), Decls.Timestamp)),
                Decls.newFunction(MATCH_TAG,
                        Decls.newOverload("resource_matchTag_string_string", List.of(Decls.String, Decls.String), Decls.Bool)),
                Decls.newFunction(MATCH_TAG_ID,
                        Decls.newOverload("resource_matchTagId_string_string", List.of(Decls.String, Decls.String), Decls.Bool)),
                Decls.newVar("resource.service", Decls.String),
                Decls.newVar("resource.type", Decls.String),
                Decls.newVar("resource.name", Decls.String),
                Decls.newVar("request.time", Decls.Timestamp));
    }

    /**
     * Supplies the finite runtime overload set paired with the profile declarations.
     *
     * <p>Keeping this explicit prevents an operator that happens to be available through a CEL
     * standard-library overload from widening the Version 1 profile at evaluation time.</p>
     */
    private static Overload[] profileOverloads() {
        Stream<Overload> standard = Arrays.stream(Overload.standardOverloads())
                .filter(overload -> RUNTIME_OPERATORS.contains(overload.operator));
        Stream<Overload> tags = Stream.of(
                Overload.binary(MATCH_TAG, (key, value) -> matchTag(key, value, (evaluation, args) -> {
                    String canonicalKey = evaluation.resolver().canonicalKey(args[0]);
                    String wanted = canonicalKey + "/" + args[1];
                    return evaluation.tags().stream().anyMatch(tag -> tag.key().equals(canonicalKey) && tag.value().equals(wanted));
                })),
                Overload.binary(MATCH_TAG_ID, (key, value) -> matchTag(key, value, (evaluation, args) ->
                        evaluation.tags().stream().anyMatch(tag -> tag.keyName().equals(args[0]) && tag.valueName().equals(args[1])))));
        return Stream.concat(standard, tags).toArray(Overload[]::new);
    }

    private static Val matchTag(Val key, Val value, BiPredicate<Evaluation, String[]> test) {
        Evaluation evaluation = CURRENT.get();
        if (evaluation == null || evaluation.resolver() == null
                || !(key.value() instanceof String k) || !(value.value() instanceof String v)) {
            return BoolT.False;
        }
        return test.test(evaluation, new String[] {k, v}) ? BoolT.True : BoolT.False;
    }

    /** The resource under evaluation, with its effective tags looked up on first use. */
    private static final class Evaluation {
        private final IamResource resource;
        private final IamTagResolver resolver;
        private List<IamTagResolver.IamTag> tags;

        Evaluation(IamResource resource, IamTagResolver resolver) {
            this.resource = resource;
            this.resolver = resolver;
        }

        IamTagResolver resolver() {
            return resolver;
        }

        List<IamTagResolver.IamTag> tags() {
            if (tags == null) {
                tags = resolver.effectiveTags(resource);
            }
            return tags;
        }
    }
}
