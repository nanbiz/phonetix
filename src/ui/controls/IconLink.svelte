<script lang="ts">
  // The same button, where what it does is go somewhere.
  //
  // A real link, so it carries its address and can be opened however a reader opens links. The
  // host is still asked first where there is one, because a page's own window is not always
  // where the surface wants a page to open.

  interface Props {
    icon: string;
    label: string;
    url: string;
    name?: string;
    /** Where the surface would rather open it. */
    open?: (url: string) => void;
  }

  let { icon, label, url, name = '', open }: Props = $props();
</script>

<a
  class="icon-btn"
  href={url}
  target="_blank"
  rel="noopener noreferrer"
  title={label}
  aria-label={label}
  data-does={name || undefined}
  onclick={(event) => {
    if (open) {
      event.preventDefault();
      open(url);
    }
  }}
>{@html icon}</a>
