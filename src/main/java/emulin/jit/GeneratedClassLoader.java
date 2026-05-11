package emulin.jit;

/**
 * ASM で生成した .class byte 列を JVM に load するための小さな ClassLoader。
 * 同名 class は重複定義不可なので、生成 class は内部で unique 名 (RIP +
 * counter) を持たせる。
 */
final class GeneratedClassLoader extends ClassLoader {
  GeneratedClassLoader() {
    // Cpu64 が見える親 (Cpu64.class.getClassLoader()) を継承
    super( emulin.Cpu64.class.getClassLoader() );
  }
  Class<?> define( String binaryName, byte[] bytes ) {
    return defineClass( binaryName, bytes, 0, bytes.length );
  }
}
