import collections
import sys
from synthetic_tokens import tokens


def counts(text):
    return sorted(collections.Counter(token.lower() for token in tokens(text)).items())


def main():
    for word, count in counts(sys.stdin.read()):
        print(f"{word}\t{count}")


if __name__ == "__main__":
    main()
