#ifdef NDEBUG
#error "USB host tests require assertions in every build profile"
#endif

#include <cassert>

int main() {
    assert(true);
    return 0;
}
