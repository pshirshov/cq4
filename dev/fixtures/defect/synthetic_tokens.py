"""Local synthetic dependency used only by the CQ evaluation."""
import re


def tokens(text):
    return re.findall(r"\w+", text)
