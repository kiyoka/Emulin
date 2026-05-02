/* cpp_dyn64.cc — C++ STL + 例外の動的リンク回帰固定 (Phase 25)
 *
 * libstdc++.so.6 / libgcc_s.so.1 を sandbox にコピーした上で:
 *   - std::vector / std::string / std::sort
 *   - std::cout の operator<< チェイン
 *   - throw / catch (std::runtime_error)
 *   - static-local guard (futex 経由)
 * を検証。Phase 25 で追加した futex (no-op) と PMAXUB の検収にもなる。
 *
 * 期待出力:
 *   apple
 *   banana
 *   cherry
 *   caught: test
 *   ctor=1
 */
#include <iostream>
#include <vector>
#include <string>
#include <algorithm>
#include <stdexcept>

struct Once {
    Once() { std::cout << "ctor=1\n"; }
};

const Once& get_once() {
    static Once o;  // static-local guard → futex 経由
    return o;
}

int main() {
    std::vector<std::string> v = {"banana", "apple", "cherry"};
    std::sort(v.begin(), v.end());
    for (const auto& s : v) std::cout << s << "\n";

    try { throw std::runtime_error("test"); }
    catch (const std::exception& e) { std::cout << "caught: " << e.what() << "\n"; }

    get_once();
    get_once();  /* 2 回目はガードで ctor を呼ばない */
    return 0;
}
