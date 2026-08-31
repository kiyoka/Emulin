// ----------------------------------------
//  Pure unsupported-host selection smoke for Apple HVF (issue #973)
// ----------------------------------------
package emulin;

public final class Aarch64HvAvailabilitySmoke {
  private Aarch64HvAvailabilitySmoke() {}

  public static void main( String[] args ) {
    require( Aarch64HvBindings.hostSupported( "Mac OS X", "aarch64" ),
        "Apple Silicon aarch64 must be supported" );
    require( Aarch64HvBindings.hostSupported( "macOS", "arm64" ),
        "Apple Silicon arm64 must be supported" );
    require( !Aarch64HvBindings.hostSupported( "Mac OS X", "x86_64" ),
        "Intel macOS must be rejected" );
    require( !Aarch64HvBindings.hostSupported( "Linux", "aarch64" ),
        "non-macOS arm64 must be rejected" );
    require( !Aarch64HvBindings.hostSupported( "Windows 11", "aarch64" ),
        "Windows arm64 must be rejected" );
    require( !Aarch64HvBindings.hostSupported( null, null ),
        "missing host identity must be rejected" );

    String currentOs = System.getProperty( "os.name" );
    String currentArch = System.getProperty( "os.arch" );
    if( !Aarch64HvBindings.hostSupported( currentOs, currentArch ) ) {
      require( !Aarch64HvBindings.probe(),
          "HVF must not be detected on the current unsupported host" );
    }

    require( CpuBackend.AUTO.effective( false ) == CpuBackend.SOFTWARE,
        "auto must fall back to software without a native backend" );
    require( CpuBackend.AUTO.effective( true ) == CpuBackend.NATIVE,
        "auto must select native when it is available" );
    require( CpuBackend.NATIVE.effective( false ) == CpuBackend.NATIVE,
        "explicit native must remain native so verification can fail clearly" );
    require( CpuBackend.SOFTWARE.effective( true ) == CpuBackend.SOFTWARE,
        "explicit software must remain canonical" );

    System.out.println( "AArch64 HVF unsupported-host selection smoke: PASS" );
  }

  private static void require( boolean condition, String message ) {
    if( !condition ) throw new AssertionError( message );
  }
}
