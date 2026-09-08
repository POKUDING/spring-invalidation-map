package dev.toktokhan.invalidation.core.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * 클래스 제네릭 시그니처에서 상위 타입별 타입 인자를 뽑습니다.
 *
 * <p>예: {@code Lcom/querydsl/core/types/dsl/EntityPathBase<Lcom/example/Run;>;} 에서
 * {@code com/querydsl/core/types/dsl/EntityPathBase -> [com/example/Run]} 를 얻습니다.
 *
 * <p>한 단계 중첩만 읽습니다. {@code List<Map<String, Run>>} 처럼 두 단계 이상 중첩된
 * 타입 인자에서는 바깥 인자만 잡힙니다. 이 라이브러리가 필요한 시그니처
 * ({@code JpaRepository<E, ID>}, {@code EntityPathBase<E>})는 모두 한 단계입니다.
 */
public final class SignatureTypeArguments {

    private static final int API = Opcodes.ASM9;

    private SignatureTypeArguments() {
    }

    public static Map<String, List<String>> parse(String classSignature) {
        if (classSignature == null || classSignature.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        new SignatureReader(classSignature).accept(new SignatureVisitor(API) {
            @Override
            public SignatureVisitor visitSuperclass() {
                return new SupertypeCollector(result);
            }

            @Override
            public SignatureVisitor visitInterface() {
                return new SupertypeCollector(result);
            }

            // 타입 파라미터 바운드(<T extends Foo>)는 상위 타입이 아니므로 버립니다.
            @Override
            public SignatureVisitor visitClassBound() {
                return new SignatureVisitor(API) {
                };
            }

            @Override
            public SignatureVisitor visitInterfaceBound() {
                return new SignatureVisitor(API) {
                };
            }
        });
        return result;
    }

    private static final class SupertypeCollector extends SignatureVisitor {

        private final Map<String, List<String>> result;
        private String owner;

        SupertypeCollector(Map<String, List<String>> result) {
            super(API);
            this.result = result;
        }

        @Override
        public void visitClassType(String name) {
            if (owner == null) {
                owner = name;
                result.computeIfAbsent(name, key -> new ArrayList<>());
            }
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            if (owner == null) {
                return new SignatureVisitor(API) {
                };
            }
            List<String> arguments = result.get(owner);
            return new SignatureVisitor(API) {
                @Override
                public void visitClassType(String name) {
                    arguments.add(name);
                }

                // 타입 인자 자체가 제네릭이면(List<Map<String, Foo>> 처럼) ASM 기본
                // 구현이 this 를 돌려줘 안쪽 클래스 타입까지 이 비지터로 들어옵니다.
                // 한 단계만 읽으므로 안쪽 타입 인자는 무시하는 격리된 비지터를 돌려줍니다.
                @Override
                public SignatureVisitor visitTypeArgument(char nestedWildcard) {
                    return new SignatureVisitor(API) {
                    };
                }
            };
        }
    }
}
